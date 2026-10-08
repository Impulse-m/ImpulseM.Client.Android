package org.telegram.tgnet.impulse.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.impulsem.proxy.LibXrayClient;
import net.impulsem.proxy.LocalInbounds;
import net.impulsem.proxy.ProxyRouting;
import net.impulsem.proxy.ProxyServer;
import net.impulsem.proxy.ProxyState;
import net.impulsem.proxy.ProxyStateCodec;
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
    private final LibXrayClient xray = new LibXrayClient(new XrayEngine());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();
    private final SecureRandom random = new SecureRandom();
    private ProxyState state;
    private volatile Status status = Status.OFF;
    private volatile String lastError;
    private volatile Runtime runtime;
    // Worker-thread only: invalidates a pending health check, and tracks the single automatic retry.
    private int generation;
    private boolean autoRetried;


    private ProxyController() {
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ImpulseProxyWorker");
            thread.setDaemon(true);
            return thread;
        });
        state = ProxyStateCodec.decode(prefs().getString(StateKey, null));
        // Until the first restart runs, an enabled proxy must not look like a direct route.
        status = state.enabled ? Status.STARTING : Status.OFF;
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
        requestRestart();
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
            lastError = "No proxy server selected";
            FileLog.d("impulse proxy: status FAILED, no selected server");
            notifyListeners();
            return;
        }

        status = Status.STARTING;
        notifyListeners();
        String stage = "freePorts";
        String failure = "Could not start proxy core";
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
                failure = "Invalid server configuration";
                throw new XrayException("invalid configuration");
            }
            stage = "test";
            failure = "Invalid server configuration";
            xray.test(config);
            stage = "run";
            failure = "Could not start proxy core";
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
        if (expectedGeneration != generation || status != Status.RUNNING) {
            return;
        }
        boolean alive;
        try {
            alive = xray.isRunning();
        } catch (XrayException e) {
            alive = false;
        }
        if (alive) {
            autoRetried = false;
            return;
        }
        if (!autoRetried) {
            autoRetried = true;
            FileLog.d("impulse proxy: core stopped, restarting once");
            restart();
            return;
        }
        runtime = null;
        lastError = "The proxy core stopped";
        status = Status.FAILED;
        FileLog.d("impulse proxy: status FAILED, core stopped again");
        notifyListeners();
    }


    private void failUnexpectedly(Throwable t) {
        runtime = null;
        lastError = "Proxy core error";
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
        ConnectionsManager.onProxyChanged();
        AndroidUtilities.runOnUIThread(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
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
        } catch (java.net.UnknownHostException e) {
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
