package net.impulsem.transport.logging;


public enum LogLevel {
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error");


    private final String label;


    LogLevel(String label) {
        this.label = label;
    }


    public String label() {
        return label;
    }


    public boolean isAtLeast(LogLevel threshold) {
        return ordinal() >= threshold.ordinal();
    }
}
