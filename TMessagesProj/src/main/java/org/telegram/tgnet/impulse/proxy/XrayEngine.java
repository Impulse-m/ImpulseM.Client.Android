package org.telegram.tgnet.impulse.proxy;

import net.impulsem.proxy.XrayRuntime;


/** libXray's native Invoke entry point. All calls go through the controller's single worker thread. */
final class XrayEngine implements XrayRuntime {

    @Override
    public String invoke(String requestJson) {
        return libXray.LibXray.invoke(requestJson);
    }
}
