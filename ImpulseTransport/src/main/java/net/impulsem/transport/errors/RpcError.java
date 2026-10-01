package net.impulsem.transport.errors;


public final class RpcError {

    public final int code;
    public final String text;


    public RpcError(
        int code,
        String text
    ) {
        this.code = code;
        this.text = text;
    }


    @Override
    public String toString() {
        return "RpcError(" + code + ", " + text + ")";
    }
}
