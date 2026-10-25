package net.impulsem.transport.codec;


public final class EncodedRequest {

    public final int methodId;
    /** The canon TL method name, e.g. messages.sendMessage; the Centrifugo registry key. */
    public final String method;
    public final String path;
    public final byte[] proto;
    public final Long takeoutId;


    public EncodedRequest(
        int methodId,
        String method,
        String path,
        byte[] proto,
        Long takeoutId
    ) {
        this.methodId = methodId;
        this.method = method;
        this.path = path;
        this.proto = proto;
        this.takeoutId = takeoutId;
    }
}
