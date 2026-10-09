package org.telegram.messenger.remotelog;

import net.impulsem.transport.logging.LogLevel;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;


/** Carries the transport module's java.util.logging output into FileLog and the remote sink. */
final class RemoteLogJulHandler extends Handler {

    private static final String RealtimeLoggerPrefix = "net.impulsem.transport.realtime";

    private final SimpleFormatter formatter = new SimpleFormatter();


    @Override
    public void publish(java.util.logging.LogRecord record) {
        if (record == null) {
            return;
        }
        LogLevel level = levelOf(record.getLevel());
        String loggerName = record.getLoggerName();
        String component = loggerName != null && loggerName.startsWith(RealtimeLoggerPrefix)
            ? RemoteLog.ComponentRealtime
            : RemoteLog.ComponentTransport;
        String message = formatter.formatMessage(record);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.local(level, component + " " + message);
        }
        RemoteLog.capture(level, component, message, record.getThrown());
    }


    @Override
    public void flush() {
    }


    @Override
    public void close() {
    }


    private static LogLevel levelOf(Level level) {
        int value = level == null ? Level.INFO.intValue() : level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return LogLevel.ERROR;
        }
        if (value >= Level.WARNING.intValue()) {
            return LogLevel.WARN;
        }
        if (value >= Level.INFO.intValue()) {
            return LogLevel.INFO;
        }
        return LogLevel.DEBUG;
    }
}
