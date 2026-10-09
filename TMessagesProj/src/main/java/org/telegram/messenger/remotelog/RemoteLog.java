package org.telegram.messenger.remotelog;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import net.impulsem.transport.logging.LogLevel;
import net.impulsem.transport.logging.LogRecord;
import net.impulsem.transport.logging.LokiShipper;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.impulse.ImpulseConnection;
import org.telegram.tgnet.impulse.ImpulseEndpoints;
import org.telegram.ui.Components.ForegroundDetector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Ships the app's diagnostics to the backend's Loki ({IMPULSEM_ENDPOINT}/loki/api/v1/push), so a trace can be read with
 * LogQL instead of being copied off the phone. Every FileLog line is captured, and {@link #trace} adds structured
 * events. On by default in debug (beta) builds; the debug menu switches it.
 */
public final class RemoteLog {

    public static final String ComponentApp = "app";
    public static final String ComponentPush = "push";
    public static final String ComponentRealtime = "realtime";
    public static final String ComponentSync = "sync";
    public static final String ComponentCalls = "calls";
    public static final String ComponentSend = "send";
    public static final String ComponentTransport = "transport";
    public static final String ComponentCrash = "crash";

    public static final long PushFlushMillis = 3000L;

    private static final String ServiceName = "ImpulseM.Android";
    private static final String PreferencesName = "remoteLog";
    private static final String EnabledKey = "enabled";
    private static final String InstallIdKey = "installId";
    private static final String TransportLoggerName = "net.impulsem.transport";
    private static final long CrashFlushMillis = 2000L;

    private static volatile LokiShipper shipper;
    private static volatile LogLevel minimumLevel = LogLevel.DEBUG;
    private static SharedPreferences preferences;
    private static Logger transportLogger;


    private RemoteLog() {
    }


    /** Called once from Application.onCreate, before the first FileLog line. */
    public static synchronized void init(Context context) {
        if (shipper != null) {
            return;
        }
        preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE);
        minimumLevel = BuildConfig.DEBUG_VERSION ? LogLevel.DEBUG : LogLevel.INFO;
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("service_name", ServiceName);
        labels.put("app_version", appVersion(context));
        labels.put("device_model", Build.MODEL == null ? "unknown" : Build.MODEL);
        labels.put("install_id", installId());
        LokiShipper created = new LokiShipper(
            ImpulseConnection::httpClient,
            LokiShipper.pushUrlFor(ImpulseEndpoints.rpcBaseUrl()),
            labels
        );
        created.setEnabled(preferences.getBoolean(EnabledKey, BuildConfig.DEBUG_VERSION));
        shipper = created;
        bridgeTransportLogging();
        flushBeforeCrash();
    }


    /** Flushes whenever the app leaves the foreground: the process may be frozen soon after. */
    public static void watchForeground(ForegroundDetector detector) {
        detector.addListener(new ForegroundDetector.Listener() {
            @Override
            public void onBecameForeground() {
            }


            @Override
            public void onBecameBackground() {
                flush();
            }
        });
    }


    public static boolean isEnabled() {
        LokiShipper current = shipper;
        return current != null && current.isEnabled();
    }


    public static void setEnabled(boolean enabled) {
        LokiShipper current = shipper;
        if (current == null) {
            return;
        }
        preferences.edit().putBoolean(EnabledKey, enabled).apply();
        current.setEnabled(enabled);
        trace(ComponentApp, enabled ? "REMOTE_LOG_ENABLED" : "REMOTE_LOG_DISABLED");
    }


    /** The FileLog sink: an unstructured line with its level. */
    public static void capture(
        LogLevel level,
        String message,
        Throwable error
    ) {
        capture(level, ComponentApp, message, error);
    }


    public static void capture(
        LogLevel level,
        String component,
        String message,
        Throwable error
    ) {
        LokiShipper current = shipper;
        if (current == null || !current.isEnabled() || !level.isAtLeast(minimumLevel)) {
            return;
        }
        current.append(new LogRecord(
            System.currentTimeMillis(),
            level,
            component,
            null,
            message,
            error,
            Thread.currentThread().getName(),
            null
        ));
    }


    /** A structured event at info level: {@code trace(ComponentCalls, "PHONE_CALL_UPDATE", "callId", id, "state", s)}. */
    public static void trace(
        String component,
        String event,
        Object... keysAndValues
    ) {
        trace(LogLevel.INFO, component, event, keysAndValues);
    }


    public static void trace(
        LogLevel level,
        String component,
        String event,
        Object... keysAndValues
    ) {
        Map<String, Object> fields = LogRecord.fieldsOf(keysAndValues);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.local(level, component + " " + event + " " + fields);
        }
        LokiShipper current = shipper;
        if (current == null || !current.isEnabled() || !level.isAtLeast(minimumLevel)) {
            return;
        }
        current.append(new LogRecord(
            System.currentTimeMillis(),
            level,
            component,
            event,
            null,
            null,
            Thread.currentThread().getName(),
            fields
        ));
    }


    public static void flush() {
        LokiShipper current = shipper;
        if (current != null && current.isEnabled()) {
            current.flush();
        }
    }


    /** Blocks the calling thread for at most {@code timeoutMillis}; never call it on the main thread. */
    public static boolean flushAndWait(long timeoutMillis) {
        LokiShipper current = shipper;
        if (current == null || !current.isEnabled()) {
            return true;
        }
        return current.flushAndWait(timeoutMillis);
    }


    private static void bridgeTransportLogging() {
        transportLogger = Logger.getLogger(TransportLoggerName);
        transportLogger.setLevel(Level.INFO);
        transportLogger.addHandler(new RemoteLogJulHandler());
    }


    /**
     * Wraps the uncaught-exception handler so the crash and the lines before it are pushed before the process dies.
     * BuildVars installs its FileLog.fatal handler on first use; touching it here keeps that handler inside this one.
     */
    private static void flushBeforeCrash() {
        boolean logsEnabled = BuildVars.LOGS_ENABLED;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            try {
                capture(LogLevel.ERROR, ComponentCrash, "uncaught exception on thread " + thread.getName() + " logsEnabled=" + logsEnabled, exception);
                flushAndWait(CrashFlushMillis);
            } catch (Throwable ignore) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, exception);
            }
        });
    }


    private static String installId() {
        String id = preferences.getString(InstallIdKey, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            preferences.edit().putString(InstallIdKey, id).apply();
        }
        return id;
    }


    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (info.versionName != null) {
                return info.versionName;
            }
        } catch (Exception ignore) {
        }
        return BuildVars.BUILD_VERSION_STRING;
    }
}
