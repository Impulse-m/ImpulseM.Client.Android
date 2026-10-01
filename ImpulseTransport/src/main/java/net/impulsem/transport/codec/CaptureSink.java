package net.impulsem.transport.codec;


public interface CaptureSink {

    void onCapture(String protoMessage, String fieldName, String value);
}
