package org.telegram.tgnet.impulse.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.impulsem.proxy.HealthPolicy;
import net.impulsem.proxy.LibXrayClient;
import net.impulsem.proxy.LocalInbounds;
import net.impulsem.proxy.ProxyRouting;
import net.impulsem.proxy.ProxyServer;
import net.impulsem.proxy.ProxyState;
import net.impulsem.proxy.ProxyStateCodec;
import net.impulsem.proxy.Subscription;
import net.impulsem.proxy.SubscriptionMeta;
import net.impulsem.proxy.XrayConfigBuilder;
import net.impulsem.proxy.XrayException;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;


/** Owns the user's VLESS settings and the in-process Xray core. */
public final class ProxyController {

    public enum Status {
        OFF,
        STARTING,
        RUNNING,
        FAILED
    }


    public interface StateChange {
        void apply(ProxyState state);
    }


    /** error is null on success; always called on the UI thread. */
    public interface Callback {
        void done(String error);
    }


    /** The loopback endpoints and credentials of the running core, published in one write. */
    public static final class Runtime {
        public final InetSocketAddress httpEndpoint;
        public final InetSocketAddress socksEndpoint;
        public final String user;
        public final String password;


        private Runtime(
            InetSocketAddress httpEndpoint,
            InetSocketAddress socksEndpoint,
            String user,
            String password
        ) {
            this.httpEndpoint = httpEndpoint;
            this.socksEndpoint = socksEndpoint;
            this.user = user;
            this.password = password;
        }
    }


    private static final String PrefsName = "impulse_proxy";
    private static final String StateKey = "state";
    private static final long HealthCheckDelaySeconds = 30L;
    private static final Object instanceLock = new Object();
    private static volatile ProxyController instance;

    private final Object lock = new Object();
    private final ScheduledExecutorService worker;
    // Subscription downloads run here, one at a time, so a slow fetch never delays a core restart.
    private final ExecutorService fetcher;
    private final LibXrayClient xray = new LibXrayClient(new XrayEngine());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();
    private final SecureRandom random = new SecureRandom();
    private ProxyState state;
    private volatile Status status = Status.OFF;
    private volatile String lastError;
    private volatile Runtime runtime;
    // What the transport last saw; guarded by lock.
    private ProxyRouting.Route publishedRoute;
    private Runtime publishedRuntime;
    // Worker-thread only: invalidates a pending health check, and tracks the single automatic retry.
    private int generation;
    private boolean autoRetried;


    private ProxyController() {
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ImpulseProxyWorker");
            thread.setDaemon(true);
            return thread;
        });
        fetcher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ImpulseSubscriptionWorker");
            thread.setDaemon(true);
            return thread;
        });
        state = ProxyStateCodec.decode(prefs().getString(StateKey, null));
        // Until the first restart runs, an enabled proxy must not look like a direct route.
        status = state.enabled ? Status.STARTING : Status.OFF;
        publishedRoute = ProxyRouting.decide(state.enabled, false);
    }


    public static ProxyController getInstance() {
        ProxyController local = instance;
        if (local == null) {
            synchronized (instanceLock) {
                local = instance;
                if (local == null) {
                    local = new ProxyController();
                    instance = local;
                }
            }
        }
        return local;
    }


    public Status status() {
        return status;
    }


    public String lastError() {
        return lastError;
    }


    public boolean isEnabled() {
        synchronized (lock) {
            return state.enabled;
        }
    }


    public boolean useForCalls() {
        synchronized (lock) {
            return state.useForCalls;
        }
    }


    public boolean hasServers() {
        synchronized (lock) {
            return !state.allServers().isEmpty();
        }
    }


    /** A decoded copy, safe to read and mutate off the lock. */
    public ProxyState snapshot() {
        synchronized (lock) {
            return copyOf(state);
        }
    }


    public void update(StateChange change) {
        boolean restartNeeded;
        synchronized (lock) {
            ProxyState next = copyOf(state);
            change.apply(next);
            restartNeeded = next.enabled != state.enabled || !sameId(next.selectedId, state.selectedId);
            state = next;
            persist(next);
            if (restartNeeded) {
                // Fail closed right away: the old server must not keep carrying traffic.
                runtime = null;
                status = Status.STARTING;
                lastError = null;
            }
        }
        if (restartNeeded) {
            requestRestart();
        } else {
            notifyListeners();
        }
    }


    public ProxyRouting.Route route() {
        boolean enabled;
        synchronized (lock) {
            enabled = state.enabled;
        }
        return ProxyRouting.decide(enabled, status == Status.RUNNING);
    }


    /** Endpoints and credentials as one consistent snapshot; null unless RUNNING. */
    public Runtime runtime() {
        Runtime current = runtime;
        return status == Status.RUNNING ? current : null;
    }


    public InetSocketAddress httpEndpoint() {
        Runtime current = runtime();
        return current == null ? null : current.httpEndpoint;
    }


    public InetSocketAddress socksEndpoint() {
        Runtime current = runtime();
        return current == null ? null : current.socksEndpoint;
    }


    public String user() {
        Runtime current = runtime();
        return current == null ? null : current.user;
    }


    public String password() {
        Runtime current = runtime();
        return current == null ? null : current.password;
    }


    /**
     * The shared client. run, stop, test and freePorts belong to the controller; callers may use only
     * convertShareLinks and pingBatch, and pingBatch must be called off the UI thread.
     */
    public LibXrayClient xray() {
        return xray;
    }


    public void addListener(Runnable listener) {
        listeners.addIfAbsent(listener);
    }


    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }


    public void startIfEnabled() {
        worker.execute(() -> {
            autoRetried = false;
            restart();
            // The core has settled; stale subscriptions can now be fetched through the right route.
            refreshDue();
        });
    }


    /** Fetches a new subscription; a failed first fetch is reported and nothing is stored. */
    public void addSubscription(
        String url,
        Callback callback
    ) {
        String trimmed = url == null ? "" : url.trim();
        String id = SubscriptionUpdater.idFor(trimmed);
        fetcher.execute(() -> {
            String error = fetchAndStore(id, trimmed, true);
            finish(callback, error);
        });
    }


    public void refresh(
        String subscriptionId,
        Callback callback
    ) {
        fetcher.execute(() -> {
            Subscription current = findSubscription(snapshot(), subscriptionId);
            String error = current == null ? ProxyErrors.SubscriptionNotFound : fetchAndStore(current.id, current.url, false);
            finish(callback, error);
        });
    }


    /** Refreshes every subscription whose update interval has passed, one after another. */
    public void refreshDue() {
        fetcher.execute(() -> {
            long now = System.currentTimeMillis();
            List<String> due = new ArrayList<String>();
            for (Subscription subscription : snapshot().subscriptions) {
                int hours = subscription.meta == null ? SubscriptionMeta.DefaultIntervalHours : subscription.meta.updateIntervalHours;
                if (subscription.updatedAt + hours * 3600000L <= now) {
                    due.add(subscription.id);
                }
            }
            for (String id : due) {
                Subscription current = findSubscription(snapshot(), id);
                if (current == null) {
                    continue;
                }
                if (route() == ProxyRouting.Route.BLOCKED) {
                    // Do not record a failure for a fetch the kill switch would block; try again later.
                    return;
                }
                fetchAndStore(current.id, current.url, false);
            }
        });
    }


    // Runs on the fetcher thread. Returns the sanitized error, or null on success.
    private String fetchAndStore(
        String id,
        String url,
        boolean allowNew
    ) {
        if (route() == ProxyRouting.Route.BLOCKED) {
            return ProxyErrors.NotRunning;
        }
        try {
            Subscription previous = findSubscription(snapshot(), id);
            Subscription fresh = SubscriptionUpdater.fetch(xray, url, previous);
            if (previous == null && (fresh.lastError != null || !allowNew)) {
                return fresh.lastError != null ? fresh.lastError : ProxyErrors.SubscriptionNotFound;
            }
            update(next -> {
                // A subscription removed while the fetch ran must stay removed.
                if (allowNew || findSubscription(next, id) != null) {
                    next.replaceSubscription(fresh);
                }
            });
            return fresh.lastError;
        } catch (Throwable t) {
            FileLog.d("impulse proxy: subscription " + id + " error, " + t.getClass().getName());
            return ProxyErrors.InvalidSubscription;
        }
    }


    private static Subscription findSubscription(
        ProxyState source,
        String id
    ) {
        for (Subscription subscription : source.subscriptions) {
            if (subscription.id.equals(id)) {
                return subscription;
            }
        }
        return null;
    }


    private static void finish(
        Callback callback,
        String error
    ) {
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> callback.done(error));
        }
    }


    private void requestRestart() {
        worker.execute(() -> {
            autoRetried = false;
            restart();
        });
    }


    // Runs on the worker thread only.
    private void restart() {
        try {
            restartCore();
        } catch (Throwable t) {
            failUnexpectedly(t);
        }
    }


    private void restartCore() {
        generation++;
        int current = generation;
        try {
            if (xray.isRunning()) {
                xray.stop();
            }
        } catch (XrayException e) {
            // A core that cannot be stopped or queried is replaced by the run below.
        }
        runtime = null;
        lastError = null;

        boolean enabled;
        ProxyServer server;
        synchronized (lock) {
            enabled = state.enabled;
            server = state.selected();
        }
        if (!enabled) {
            status = Status.OFF;
            FileLog.d("impulse proxy: status OFF");
            notifyListeners();
            return;
        }
        if (server == null) {
            status = Status.FAILED;
            lastError = ProxyErrors.NoServer;
            FileLog.d("impulse proxy: status FAILED, no selected server");
            notifyListeners();
            return;
        }

        status = Status.STARTING;
        notifyListeners();
        String stage = "freePorts";
        String failure = ProxyErrors.CoreStart;
        try {
            int[] ports = xray.freePorts(2);
            if (ports.length < 2) {
                throw new XrayException("not enough ports");
            }
            String newUser = randomHex(16);
            String newPassword = randomHex(24);
            String config;
            try {
                config = XrayConfigBuilder.build(server, new LocalInbounds(ports[0], ports[1], newUser, newPassword));
            } catch (RuntimeException e) {
                stage = "build";
                failure = ProxyErrors.InvalidConfig;
                throw new XrayException("invalid configuration");
            }
            stage = "test";
            failure = ProxyErrors.InvalidConfig;
            xray.test(config);
            stage = "run";
            failure = ProxyErrors.CoreStart;
            xray.run(config);
            // The core listens on 127.0.0.1 only; getLoopbackAddress() may return ::1 on Android.
            InetAddress loopback = loopbackV4();
            runtime = new Runtime(
                new InetSocketAddress(loopback, ports[0]),
                new InetSocketAddress(loopback, ports[1]),
                newUser,
                newPassword
            );
            status = Status.RUNNING;
            FileLog.d("impulse proxy: status RUNNING, server " + server.name + " (" + server.host + ")");
            worker.schedule(() -> checkAlive(current), HealthCheckDelaySeconds, TimeUnit.SECONDS);
        } catch (XrayException e) {
            // The libXray message can quote config fragments, so only fixed text reaches the UI.
            runtime = null;
            lastError = failure;
            status = Status.FAILED;
            FileLog.d("impulse proxy: status FAILED at " + stage + ", server " + server.name + " (" + server.host + ")");
        }
        notifyListeners();
    }


    // Runs on the worker thread only.
    private void checkAlive(int expectedGeneration) {
        try {
            checkAliveCore(expectedGeneration);
        } catch (Throwable t) {
            failUnexpectedly(t);
        }
    }


    private void checkAliveCore(int expectedGeneration) {
        boolean current = expectedGeneration == generation && status == Status.RUNNING;
        boolean alive = false;
        if (current) {
            try {
                alive = xray.isRunning();
            } catch (XrayException e) {
                alive = false;
            }
        }
        HealthPolicy.Action action = HealthPolicy.decide(current, autoRetried, alive);
        if (action == HealthPolicy.Action.STOP) {
            return;
        }
        if (action == HealthPolicy.Action.RECHECK) {
            // A full healthy check earns a later crash its own automatic restart.
            autoRetried = false;
            worker.schedule(() -> checkAlive(expectedGeneration), HealthCheckDelaySeconds, TimeUnit.SECONDS);
            return;
        }
        if (action == HealthPolicy.Action.RESTART) {
            autoRetried = true;
            FileLog.d("impulse proxy: core stopped, restarting once");
            restart();
            return;
        }
        runtime = null;
        lastError = ProxyErrors.CoreStopped;
        status = Status.FAILED;
        FileLog.d("impulse proxy: status FAILED, core stopped again");
        notifyListeners();
    }


    private void failUnexpectedly(Throwable t) {
        runtime = null;
        lastError = ProxyErrors.CoreError;
        status = Status.FAILED;
        FileLog.d("impulse proxy: status FAILED, " + t.getClass().getName());
        notifyListeners();
    }


    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE);
    }


    private void persist(ProxyState toSave) {
        prefs().edit().putString(StateKey, ProxyStateCodec.encode(toSave)).apply();
    }


    private void notifyListeners() {
        publishToTransport();
        AndroidUtilities.runOnUIThread(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }


    /** Tells the transport only when the route or the running core changed; a rename or reorder must not drop sockets. */
    private void publishToTransport() {
        ProxyRouting.Route currentRoute = route();
        Runtime currentRuntime = runtime();
        boolean changed;
        synchronized (lock) {
            changed = currentRoute != publishedRoute || currentRuntime != publishedRuntime;
            publishedRoute = currentRoute;
            publishedRuntime = currentRuntime;
        }
        if (changed) {
            ConnectionsManager.onProxyChanged();
        }
    }


    private String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        StringBuilder hex = new StringBuilder(bytes * 2);
        for (byte value : data) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }


    private static InetAddress loopbackV4() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }


    private static ProxyState copyOf(ProxyState source) {
        return ProxyStateCodec.decode(ProxyStateCodec.encode(source));
    }


    private static boolean sameId(
        String a,
        String b
    ) {
        return a == null ? b == null : a.equals(b);
    }
}
