package net.impulsem.transport.codec;


public final class EncodedRequest {

    public final int methodId;
    public final String path;
    public final byte[] proto;
    public final Long takeoutId;


    public EncodedRequest(
        int methodId,
        String path,
        byte[] proto,
        Long takeoutId
    ) {
        this.methodId = methodId;
        this.path = path;
        this.proto = proto;
        this.takeoutId = takeoutId;
    }
}
