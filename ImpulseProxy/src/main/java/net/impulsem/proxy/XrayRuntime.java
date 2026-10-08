package net.impulsem.proxy;


/** The single native entry point of libXray: libXray.LibXray.invoke(String). */
public interface XrayRuntime {
    String invoke(String requestJson);
}
