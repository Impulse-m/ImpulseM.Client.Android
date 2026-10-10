package net.impulsem.transport.logging;


/**
 * Receives the structured traces of the transport module. The embedding app decides where they go; the module itself
 * only emits typed key and value pairs, never free text.
 */
public interface TraceSink {

    /** Drops every trace. */
    TraceSink None = new TraceSink() {
        @Override
        public void trace(
            LogLevel level,
            String event,
            Object... keysAndValues
        ) {
        }
    };


    void trace(
        LogLevel level,
        String event,
        Object... keysAndValues
    );
}
