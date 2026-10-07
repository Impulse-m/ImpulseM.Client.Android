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
    private volatile InetSocketAddress httpEndpoint;
    private volatile InetSocketAddress socksEndpoint;
    private volatile String user;
    private volatile String password;
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


    public InetSocketAddress httpEndpoint() {
        return status == Status.RUNNING ? httpEndpoint : null;
    }


    public InetSocketAddress socksEndpoint() {
        return status == Status.RUNNING ? socksEndpoint : null;
    }


    public String user() {
        return status == Status.RUNNING ? user : null;
    }


    public String password() {
        return status == Status.RUNNING ? password : null;
    }


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
        generation++;
        int current = generation;
        try {
            if (xray.isRunning()) {
                xray.stop();
            }
        } catch (XrayException e) {
            // A core that cannot be stopped or queried is replaced by the run below.
        }
        clearRuntime();
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
        try {
            int[] ports = xray.freePorts(2);
            if (ports.length < 2) {
                throw new XrayException("getFreePorts: not enough ports");
            }
            String newUser = randomHex(16);
            String newPassword = randomHex(24);
            String config;
            try {
                config = XrayConfigBuilder.build(server, new LocalInbounds(ports[0], ports[1], newUser, newPassword));
            } catch (RuntimeException e) {
                throw new XrayException("Invalid server configuration");
            }
            xray.test(config);
            xray.run(config);
            InetAddress loopback = InetAddress.getLoopbackAddress();
            httpEndpoint = new InetSocketAddress(loopback, ports[0]);
            socksEndpoint = new InetSocketAddress(loopback, ports[1]);
            user = newUser;
            password = newPassword;
            status = Status.RUNNING;
            FileLog.d("impulse proxy: status RUNNING, server " + server.name + " (" + server.host + ")");
            worker.schedule(() -> checkAlive(current), HealthCheckDelaySeconds, TimeUnit.SECONDS);
        } catch (XrayException e) {
            clearRuntime();
            lastError = e.getMessage();
            status = Status.FAILED;
            FileLog.d("impulse proxy: status FAILED, server " + server.name + " (" + server.host + ")");
        }
        notifyListeners();
    }


    // Runs on the worker thread only.
    private void checkAlive(int expectedGeneration) {
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
        clearRuntime();
        lastError = "The proxy core stopped";
        status = Status.FAILED;
        FileLog.d("impulse proxy: status FAILED, core stopped again");
        notifyListeners();
    }


    private void clearRuntime() {
        httpEndpoint = null;
        socksEndpoint = null;
        user = null;
        password = null;
    }


    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE);
    }


    private void persist(ProxyState toSave) {
        prefs().edit().putString(StateKey, ProxyStateCodec.encode(toSave)).apply();
    }


    private void notifyListeners() {
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
