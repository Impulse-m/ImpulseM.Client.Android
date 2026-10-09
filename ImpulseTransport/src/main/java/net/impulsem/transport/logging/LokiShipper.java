package net.impulsem.transport.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
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
 *
 * <p>A request carries at most {@link #MaxRecordsPerRequest} records and {@link #MaxBodyBytes} bytes. The access token
 * is attached when a session exists; a token the server rejected with 401 is not sent again, so the push falls back to
 * the anonymous lane until the session gets a new one.
 */
public final class LokiShipper {

    public static final int BatchSize = 20;
    public static final long FlushIntervalMillis = 2000L;
    public static final int MaxBufferedRecords = 1000;
    public static final int MaxRecordsPerRequest = 200;
    public static final int MaxBodyBytes = 256 * 1024;
    public static final long MaxRetryAfterMillis = 600000L;
    public static final String PushPath = "/loki/api/v1/push";

    private static final String ShipperComponent = "logging";
    private static final String LossEvent = "REMOTE_LOG_RECORDS_LOST";
    private static final String DroppedField = "droppedBufferFull";
    private static final String RejectedField = "rejectedByServer";
    private static final String ShipperThreadName = "remote-log";
    private static final long RetryInitialMillis = 2000L;
    private static final long RetryMaxMillis = 60000L;
    private static final double RetryJitter = 0.2;
    private static final long CallTimeoutSeconds = 15L;
    private static final int Unauthorized = 401;
    private static final int TooManyRequests = 429;
    private static final int ClientErrorFloor = 400;
    private static final int ServerErrorFloor = 500;
    private static final String HeaderAuthorization = "Authorization";
    private static final String HeaderRetryAfter = "Retry-After";
    private static final MediaType Json = MediaType.get("application/json; charset=utf-8");
    private static final Clock SystemClock = new Clock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };


    /** The current access token of the session, or null before login. Called on the shipper thread. */
    public interface TokenSource {

        String bearer();
    }


    /** A monotonic clock; wall-clock jumps must not move the retry schedule. */
    interface Clock {

        long nanoTime();
    }


    private enum Outcome {
        DELIVERED,
        REJECTED,
        UNAUTHORIZED,
        RATE_LIMITED,
        FAILED
    }


    private static final class PostResult {

        final Outcome outcome;
        final long retryAfterMillis;


        PostResult(
            Outcome outcome,
            long retryAfterMillis
        ) {
            this.outcome = outcome;
            this.retryAfterMillis = retryAfterMillis;
        }
    }


    private final Callable<OkHttpClient> httpSource;
    private final HttpUrl pushUrl;
    private final Map<String, String> labels;
    private final Map<String, String> context;
    private final TokenSource tokens;
    private final Clock clock;
    private final int maxBodyBytes;
    private final ScheduledThreadPoolExecutor executor;
    private final Object lock = new Object();
    private final ArrayDeque<LogRecord> buffer = new ArrayDeque<LogRecord>();
    private final Backoff retryBackoff;

    private volatile Thread shipperThread;
    private volatile boolean enabled = true;
    private OkHttpClient http;
    private ScheduledFuture<?> scheduledDrain;
    private Runnable scheduledTask;
    private long scheduledDrainAt;
    private boolean retryPending;
    private long retryAt;
    private int droppedRecords;
    private int rejectedRecords;
    private String rejectedToken;


    /**
     * @param labels  the Loki stream labels shared by every record (the service name)
     * @param context plain fields written into every log line (app version, device model, install id)
     * @param tokens  the access token of the current session; may be null
     */
    public LokiShipper(
        Callable<OkHttpClient> httpSource,
        HttpUrl pushUrl,
        Map<String, String> labels,
        Map<String, String> context,
        TokenSource tokens
    ) {
        this(
            httpSource,
            pushUrl,
            labels,
            context,
            tokens,
            new Backoff(RetryInitialMillis, RetryMaxMillis, RetryJitter, new Random()),
            SystemClock,
            MaxBodyBytes
        );
    }


    LokiShipper(
        Callable<OkHttpClient> httpSource,
        HttpUrl pushUrl,
        Map<String, String> labels,
        Map<String, String> context,
        TokenSource tokens,
        Backoff retryBackoff,
        Clock clock,
        int maxBodyBytes
    ) {
        this.httpSource = httpSource;
        this.pushUrl = pushUrl;
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<String, String>(labels));
        this.context = Collections.unmodifiableMap(new LinkedHashMap<String, String>(context));
        this.tokens = tokens;
        this.retryBackoff = retryBackoff;
        this.clock = clock;
        this.maxBodyBytes = maxBodyBytes;
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


    /** Turning it off at once stops shipping and forgets everything queued, the loss counters included. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            synchronized (lock) {
                buffer.clear();
                droppedRecords = 0;
                rejectedRecords = 0;
                retryPending = false;
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


    /**
     * Pushes what is buffered now, without waiting. A failing endpoint or a rate limit still holds it back until the
     * retry time.
     */
    public void flush() {
        synchronized (lock) {
            if (!buffer.isEmpty()) {
                scheduleLocked(0L, true);
            }
        }
    }


    /**
     * Pushes what is buffered and waits for it, at most {@code timeoutMillis}, ignoring a pending retry time. For the
     * moment before the process may be frozen or killed; never call it on the main thread.
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
                drain(true, true);
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


    private static boolean isLossReport(LogRecord record) {
        return ShipperComponent.equals(record.component) && LossEvent.equals(record.event);
    }


    private void scheduleLocked(
        long delayMillis,
        final boolean drainAll
    ) {
        long now = clock.nanoTime();
        long dueAt = now + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        if (retryPending && dueAt - retryAt < 0L) {
            dueAt = retryAt;
        }
        boolean pendingInFuture = scheduledDrain != null
            && !scheduledDrain.isDone()
            && scheduledDrainAt - now > 0L;
        if (pendingInFuture && scheduledDrainAt - dueAt <= 0L) {
            return;
        }
        cancelScheduledLocked();
        scheduledDrainAt = dueAt;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (scheduledTask == this) {
                        scheduledTask = null;
                        scheduledDrain = null;
                    }
                }
                drain(drainAll, false);
            }
        };
        scheduledTask = task;
        scheduledDrain = executor.schedule(task, Math.max(0L, dueAt - now), TimeUnit.NANOSECONDS);
    }


    private void cancelScheduledLocked() {
        if (scheduledDrain != null) {
            scheduledDrain.cancel(false);
        }
        scheduledDrain = null;
        scheduledTask = null;
    }


    /** Runs only on the shipper thread. */
    private void drain(
        boolean drainAll,
        boolean ignoreRetry
    ) {
        while (true) {
            List<LogRecord> batch = new ArrayList<LogRecord>();
            synchronized (lock) {
                if (buffer.isEmpty() || !enabled) {
                    return;
                }
                if (!ignoreRetry && retryPending && clock.nanoTime() - retryAt < 0L) {
                    scheduleLocked(0L, drainAll);
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
            String body = fitToBodyLimit(batch);
            if (batch.isEmpty()) {
                continue;
            }
            PostResult result = body == null ? new PostResult(Outcome.REJECTED, -1L) : post(body);
            synchronized (lock) {
                if (!enabled) {
                    return;
                }
                if (result.outcome == Outcome.FAILED || result.outcome == Outcome.RATE_LIMITED) {
                    requeueLocked(batch);
                    long delayMillis = result.retryAfterMillis >= 0L
                        ? Math.min(result.retryAfterMillis, MaxRetryAfterMillis)
                        : retryBackoff.nextDelayMillis();
                    retryAt = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
                    retryPending = true;
                    scheduleLocked(0L, false);
                    return;
                }
                retryPending = false;
                retryBackoff.reset();
                if (result.outcome != Outcome.DELIVERED) {
                    discardLocked(batch);
                }
                if (buffer.size() < BatchSize && !drainAll) {
                    if (!buffer.isEmpty()) {
                        scheduleLocked(FlushIntervalMillis, false);
                    }
                    return;
                }
            }
        }
    }


    /**
     * Renders the batch, and while the body is over the limit puts the second half back at the head of the buffer. A
     * single record that alone is over the limit is dropped and counted as rejected, which leaves the batch empty.
     *
     * @return the body, or null when the batch is empty or cannot be rendered at all
     */
    private String fitToBodyLimit(List<LogRecord> batch) {
        while (!batch.isEmpty()) {
            String body;
            try {
                body = LokiPayload.build(batch, labels, context);
            } catch (RuntimeException e) {
                return null;
            }
            if (body.getBytes(StandardCharsets.UTF_8).length <= maxBodyBytes) {
                return body;
            }
            synchronized (lock) {
                if (batch.size() == 1) {
                    discardLocked(batch);
                    batch.clear();
                    return null;
                }
                int keep = (batch.size() + 1) / 2;
                List<LogRecord> tail = new ArrayList<LogRecord>(batch.subList(keep, batch.size()));
                batch.subList(keep, batch.size()).clear();
                requeueLocked(tail);
            }
        }
        return null;
    }


    private LogRecord lossReportLocked() {
        if (droppedRecords == 0 && rejectedRecords == 0) {
            return null;
        }
        Map<String, Object> fields = LogRecord.fieldsOf(
            DroppedField, droppedRecords,
            RejectedField, rejectedRecords
        );
        droppedRecords = 0;
        rejectedRecords = 0;
        return new LogRecord(
            System.currentTimeMillis(),
            LogLevel.WARN,
            ShipperComponent,
            LossEvent,
            null,
            null,
            null,
            fields
        );
    }


    /** Counts a batch that will not be retried as rejected; a loss report in it gives its counts back. */
    private void discardLocked(List<LogRecord> batch) {
        for (LogRecord record : batch) {
            if (isLossReport(record)) {
                restoreLossLocked(record);
            } else {
                rejectedRecords++;
            }
        }
    }


    private void restoreLossLocked(LogRecord report) {
        droppedRecords += countOf(report, DroppedField);
        rejectedRecords += countOf(report, RejectedField);
    }


    private static int countOf(
        LogRecord report,
        String field
    ) {
        Object value = report.fields.get(field);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }


    /**
     * Puts unsent records back at the head of the buffer, oldest first, dropping the oldest when the buffer is full. A
     * loss report is not requeued; its counts go back to the counters, so the next push reports them.
     */
    private void requeueLocked(List<LogRecord> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            LogRecord record = batch.get(i);
            if (isLossReport(record)) {
                restoreLossLocked(record);
                continue;
            }
            if (buffer.size() >= MaxBufferedRecords) {
                for (int j = 0; j <= i; j++) {
                    LogRecord lost = batch.get(j);
                    if (isLossReport(lost)) {
                        restoreLossLocked(lost);
                    } else {
                        droppedRecords++;
                    }
                }
                return;
            }
            buffer.addFirst(record);
        }
    }


    private PostResult post(String body) {
        String token = effectiveToken();
        Request.Builder builder = new Request.Builder()
            .url(pushUrl)
            .post(RequestBody.create(body, Json));
        if (token != null) {
            builder.header(HeaderAuthorization, "Bearer " + token);
        }
        try {
            OkHttpClient client = client();
            try (Response response = client.newCall(builder.build()).execute()) {
                int code = response.code();
                if (response.isSuccessful()) {
                    return new PostResult(Outcome.DELIVERED, -1L);
                }
                if (code == Unauthorized) {
                    if (token != null) {
                        rejectedToken = token;
                    }
                    return new PostResult(Outcome.UNAUTHORIZED, -1L);
                }
                if (code == TooManyRequests) {
                    return new PostResult(Outcome.RATE_LIMITED, retryAfterMillis(response));
                }
                if (code >= ClientErrorFloor && code < ServerErrorFloor) {
                    return new PostResult(Outcome.REJECTED, -1L);
                }
                return new PostResult(Outcome.FAILED, -1L);
            }
        } catch (IOException | RuntimeException e) {
            return new PostResult(Outcome.FAILED, -1L);
        }
    }


    /** The session token to send, or null when there is none or the server already rejected this very token. */
    private String effectiveToken() {
        if (tokens == null) {
            return null;
        }
        String token;
        try {
            token = tokens.bearer();
        } catch (RuntimeException e) {
            return null;
        }
        if (token == null || token.isEmpty() || token.equals(rejectedToken)) {
            return null;
        }
        return token;
    }


    /** Retry-After as delta seconds or an HTTP date, in milliseconds from now; -1 when absent or unreadable. */
    private static long retryAfterMillis(Response response) {
        String header = response.header(HeaderRetryAfter);
        if (header == null) {
            return -1L;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds < 0L) {
                return -1L;
            }
            return TimeUnit.SECONDS.toMillis(Math.min(seconds, MaxRetryAfterMillis / 1000L));
        } catch (NumberFormatException e) {
            Date date = response.headers().getDate(HeaderRetryAfter);
            if (date == null) {
                return -1L;
            }
            return Math.max(0L, date.getTime() - System.currentTimeMillis());
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
