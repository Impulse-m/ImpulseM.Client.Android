package net.impulsem.proxy;


/** A libXray call failed. The message is "<method>: <libXray error>" and never carries config contents. */
public final class XrayException extends Exception {

    public XrayException(String message) {
        super(message);
    }
}
