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
 * Ships structured traces and exceptions to the backend's Loki ({IMPULSEM_ENDPOINT}/loki/api/v1/push). Free-text lines
 * stay in the local log file only. Beta builds only (BuildConfig.DEBUG_VERSION), and off until the user turns it on in
 * Settings > Privacy and Security; a release build never creates the shipper at all.
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

    private static final String ServiceName = "ImpulseM.Android";
    private static final String PreferencesName = "remoteLog";
    private static final String OptInKey = "optIn";
    private static final String InstallIdKey = "installId";
    private static final String TransportLoggerName = "net.impulsem.transport";
    private static final long CrashFlushMillis = 2000L;
    private static final LogLevel MinimumLevel = LogLevel.DEBUG;

    private static volatile LokiShipper shipper;
    private static boolean initialized;
    private static SharedPreferences preferences;
    private static Logger transportLogger;


    private RemoteLog() {
    }


    /**
     * Called once from Application.onCreate, before the first FileLog line. A release build gets no shipper, so nothing
     * can ship from it; a beta build gets one that stays off until the user opts in.
     */
    public static synchronized void init(Context context) {
        if (initialized) {
            return;
        }
        initialized = true;
        bridgeTransportLogging();
        if (!BuildConfig.DEBUG_VERSION) {
            return;
        }
        preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("service_name", ServiceName);
        Map<String, String> lineFields = new LinkedHashMap<>();
        lineFields.put("app", appVersion(context));
        lineFields.put("device", Build.MODEL == null ? "unknown" : Build.MODEL);
        lineFields.put("install", installId());
        LokiShipper created = new LokiShipper(
            ImpulseConnection::httpClient,
            LokiShipper.pushUrlFor(ImpulseEndpoints.rpcBaseUrl()),
            labels,
            lineFields,
            ImpulseConnection::processBearer
        );
        created.setEnabled(preferences.getBoolean(OptInKey, false));
        shipper = created;
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


    /** True in a beta build, the only kind that can ship; the settings row is shown only then. */
    public static boolean isAvailable() {
        return shipper != null;
    }


    public static boolean isEnabled() {
        LokiShipper current = shipper;
        return current != null && current.isEnabled();
    }


    /** The user's opt-in. Turning it off stops shipping at once and drops whatever is queued; nothing is sent about it. */
    public static void setEnabled(boolean enabled) {
        LokiShipper current = shipper;
        if (current == null) {
            return;
        }
        preferences.edit().putBoolean(OptInKey, enabled).apply();
        current.setEnabled(enabled);
        if (enabled) {
            trace(ComponentApp, "REMOTE_LOG_ENABLED");
        }
    }


    /**
     * An exception with optional typed fields. Only its class and stack frames ship; its message never does, because
     * messages carry data.
     */
    public static void captureException(
        LogLevel level,
        String component,
        Throwable error,
        Object... keysAndValues
    ) {
        LokiShipper current = shipper;
        if (error == null || current == null || !current.isEnabled() || !level.isAtLeast(MinimumLevel)) {
            return;
        }
        current.append(new LogRecord(
            System.currentTimeMillis(),
            level,
            component,
            null,
            null,
            error,
            null,
            LogRecord.fieldsOf(keysAndValues)
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
        LokiShipper current = shipper;
        boolean ships = current != null && current.isEnabled() && level.isAtLeast(MinimumLevel);
        if (!ships && !BuildVars.LOGS_ENABLED) {
            return;
        }
        Map<String, Object> fields = LogRecord.fieldsOf(keysAndValues);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.local(level, component + " " + event + " " + fields);
        }
        if (!ships) {
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
                captureException(LogLevel.ERROR, ComponentCrash, exception, "logsEnabled", logsEnabled);
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
