package net.impulsem.transport.logging;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.impulsem.transport.realtime.Backoff;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


/**
 * Buffers log records and pushes them to Loki from one background thread: a batch goes out once 20 records are
 * waiting or 2 s after the first one, a failed batch is requeued and retried with a growing delay, and the buffer is
 * bounded so a dead endpoint cannot grow it without limit. {@link #append} only takes a lock and never blocks on the
 * network.
 */
public final class LokiShipper {

    public static final int BatchSize = 20;
    public static final long FlushIntervalMillis = 2000L;
    public static final int MaxBufferedRecords = 1000;
    public static final int MaxRecordsPerRequest = 200;
    public static final String PushPath = "/loki/api/v1/push";

    private static final String ShipperComponent = "logging";
    private static final String ShipperThreadName = "remote-log";
    private static final long RetryInitialMillis = 2000L;
    private static final long RetryMaxMillis = 60000L;
    private static final double RetryJitter = 0.2;
    private static final long CallTimeoutSeconds = 15L;
    private static final int TooManyRequests = 429;
    private static final int ClientErrorFloor = 400;
    private static final int ServerErrorFloor = 500;
    private static final MediaType Json = MediaType.get("application/json; charset=utf-8");


    private enum Outcome {
        DELIVERED,
        REJECTED,
        FAILED
    }


    private final Callable<OkHttpClient> httpSource;
    private final HttpUrl pushUrl;
    private final Map<String, String> labels;
    private final ScheduledThreadPoolExecutor executor;
    private final Object lock = new Object();
    private final ArrayDeque<LogRecord> buffer = new ArrayDeque<LogRecord>();
    private final Backoff retryBackoff;

    private volatile Thread shipperThread;
    private volatile boolean enabled = true;
    private OkHttpClient http;
    private ScheduledFuture<?> scheduledDrain;
    private long scheduledDrainAt;
    private long retryAt;
    private int droppedRecords;
    private int rejectedRecords;


    public LokiShipper(
        Callable<OkHttpClient> httpSource,
        HttpUrl pushUrl,
        Map<String, String> labels
    ) {
        this(httpSource, pushUrl, labels, new Backoff(RetryInitialMillis, RetryMaxMillis, RetryJitter, new Random()));
    }


    LokiShipper(
        Callable<OkHttpClient> httpSource,
        HttpUrl pushUrl,
        Map<String, String> labels,
        Backoff retryBackoff
    ) {
        this.httpSource = httpSource;
        this.pushUrl = pushUrl;
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<String, String>(labels));
        this.retryBackoff = retryBackoff;
        this.executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, ShipperThreadName);
                thread.setDaemon(true);
                shipperThread = thread;
                return thread;
            }
        });
        this.executor.setRemoveOnCancelPolicy(true);
    }


    /** The Loki push endpoint the gateway publishes on the backend origin. */
    public static HttpUrl pushUrlFor(HttpUrl backendOrigin) {
        return backendOrigin.newBuilder().encodedPath(PushPath).query(null).build();
    }


    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            synchronized (lock) {
                buffer.clear();
                cancelScheduledLocked();
            }
        }
    }


    public boolean isEnabled() {
        return enabled;
    }


    /**
     * Queues a record that carries something shippable: a structured event or an exception. Free text has neither and is
     * never queued. A record logged on the shipper's own thread (the HTTP stack reporting on the push itself) is
     * dropped, so a failing push cannot feed itself.
     */
    public void append(LogRecord record) {
        if (!enabled || Thread.currentThread() == shipperThread || !carriesShippableData(record)) {
            return;
        }
        synchronized (lock) {
            if (buffer.size() >= MaxBufferedRecords) {
                buffer.pollFirst();
                droppedRecords++;
            }
            buffer.addLast(record);
            scheduleLocked(buffer.size() >= BatchSize ? 0L : FlushIntervalMillis, false);
        }
    }


    /** Pushes what is buffered now, without waiting, even while a failed push is waiting for its retry. */
    public void flush() {
        synchronized (lock) {
            if (!buffer.isEmpty()) {
                scheduleLocked(0L, true);
            }
        }
    }


    /**
     * Pushes what is buffered and waits for it, at most {@code timeoutMillis}. For the moment before the process may
     * be frozen or killed; never call it on the main thread.
     *
     * @return true when the buffer was empty afterwards
     */
    public boolean flushAndWait(long timeoutMillis) {
        if (Thread.currentThread() == shipperThread) {
            return false;
        }
        Future<?> drained = executor.submit(new Runnable() {
            @Override
            public void run() {
                drain(true);
            }
        });
        try {
            drained.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
        synchronized (lock) {
            return buffer.isEmpty();
        }
    }


    public int bufferedCount() {
        synchronized (lock) {
            return buffer.size();
        }
    }


    private static boolean carriesShippableData(LogRecord record) {
        return record.event != null || record.error != null;
    }


    private void scheduleLocked(
        long delayMillis,
        final boolean forced
    ) {
        long now = System.currentTimeMillis();
        long dueAt = forced ? now + delayMillis : Math.max(now + delayMillis, retryAt);
        if (scheduledDrain != null && !scheduledDrain.isDone() && scheduledDrainAt <= dueAt) {
            return;
        }
        cancelScheduledLocked();
        scheduledDrainAt = dueAt;
        scheduledDrain = executor.schedule(new Runnable() {
            @Override
            public void run() {
                drain(forced);
            }
        }, Math.max(0L, dueAt - now), TimeUnit.MILLISECONDS);
    }


    private void cancelScheduledLocked() {
        if (scheduledDrain != null) {
            scheduledDrain.cancel(false);
            scheduledDrain = null;
        }
    }


    /** Runs only on the shipper thread. */
    private void drain(boolean forced) {
        while (true) {
            List<LogRecord> batch = new ArrayList<LogRecord>();
            synchronized (lock) {
                long now = System.currentTimeMillis();
                if (scheduledDrain != null && scheduledDrainAt <= now) {
                    scheduledDrain = null;
                }
                if (buffer.isEmpty() || !enabled) {
                    return;
                }
                if (!forced && now < retryAt) {
                    scheduleLocked(0L, false);
                    return;
                }
                LogRecord loss = lossReportLocked();
                if (loss != null) {
                    batch.add(loss);
                }
                while (batch.size() < MaxRecordsPerRequest && !buffer.isEmpty()) {
                    batch.add(buffer.pollFirst());
                }
            }
            Outcome outcome = post(batch);
            synchronized (lock) {
                if (outcome == Outcome.FAILED) {
                    requeueLocked(batch);
                    retryAt = System.currentTimeMillis() + retryBackoff.nextDelayMillis();
                    scheduleLocked(0L, false);
                    return;
                }
                retryAt = 0L;
                retryBackoff.reset();
                if (outcome == Outcome.REJECTED) {
                    rejectedRecords += batch.size();
                }
                if (buffer.size() < BatchSize && !forced) {
                    if (!buffer.isEmpty()) {
                        scheduleLocked(FlushIntervalMillis, false);
                    }
                    return;
                }
            }
        }
    }


    private LogRecord lossReportLocked() {
        if (droppedRecords == 0 && rejectedRecords == 0) {
            return null;
        }
        Map<String, Object> fields = LogRecord.fieldsOf(
            "droppedBufferFull", droppedRecords,
            "rejectedByServer", rejectedRecords
        );
        droppedRecords = 0;
        rejectedRecords = 0;
        return new LogRecord(
            System.currentTimeMillis(),
            LogLevel.WARN,
            ShipperComponent,
            "REMOTE_LOG_RECORDS_LOST",
            null,
            null,
            null,
            fields
        );
    }


    /** Puts a failed batch back at the head of the buffer, oldest first, dropping the oldest when the buffer is full. */
    private void requeueLocked(List<LogRecord> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            LogRecord record = batch.get(i);
            if (ShipperComponent.equals(record.component)) {
                continue;
            }
            if (buffer.size() >= MaxBufferedRecords) {
                droppedRecords += i + 1;
                return;
            }
            buffer.addFirst(record);
        }
    }


    private Outcome post(List<LogRecord> batch) {
        String body;
        try {
            body = LokiPayload.build(batch, labels);
        } catch (RuntimeException e) {
            return Outcome.REJECTED;
        }
        Request request = new Request.Builder()
            .url(pushUrl)
            .post(RequestBody.create(body, Json))
            .build();
        try {
            OkHttpClient client = client();
            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                if (response.isSuccessful()) {
                    return Outcome.DELIVERED;
                }
                if (code >= ClientErrorFloor && code < ServerErrorFloor && code != TooManyRequests) {
                    return Outcome.REJECTED;
                }
                return Outcome.FAILED;
            }
        } catch (IOException | RuntimeException e) {
            return Outcome.FAILED;
        }
    }


    private OkHttpClient client() throws IOException {
        if (http == null) {
            OkHttpClient base;
            try {
                base = httpSource.call();
            } catch (Exception e) {
                throw new IOException("no http client for the log push", e);
            }
            http = base.newBuilder().callTimeout(CallTimeoutSeconds, TimeUnit.SECONDS).build();
        }
        return http;
    }
}
