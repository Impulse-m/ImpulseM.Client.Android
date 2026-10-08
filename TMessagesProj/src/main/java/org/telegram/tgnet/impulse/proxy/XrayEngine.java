package org.telegram.tgnet.impulse.proxy;

import net.impulsem.proxy.XrayRuntime;


/** libXray's native Invoke entry point. Safe to call from any thread: run, stop and test go through the controller's worker, while convertShareLinks and pingBatch also run on the fetcher and UI executors. */
final class XrayEngine implements XrayRuntime {

    @Override
    public String invoke(String requestJson) {
        return libXray.LibXray.invoke(requestJson);
    }
}
