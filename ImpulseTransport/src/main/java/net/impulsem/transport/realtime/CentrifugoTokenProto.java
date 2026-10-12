package net.impulsem.transport.realtime;

import java.io.UnsupportedEncodingException;
import net.impulsem.transport.wire.ProtoReader;


/** Protobuf body of the GetCentrifugoToken reply, which the realtime layer decodes into a connection token. */
public final class CentrifugoTokenProto {

    private CentrifugoTokenProto() {
    }


    /** GetCentrifugoTokenResponse { string token = 1; int64 expires_in = 2; }; null when no token is present. */
    public static String decodeToken(byte[] proto) {
        ProtoReader reader = new ProtoReader(proto, 0, proto.length);
        while (reader.next()) {
            if (reader.field() == 1 && reader.wireType() == 2) {
                return string(reader.readBytes());
            }
        }
        return null;
    }


    private static String string(byte[] value) {
        try {
            return new String(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
