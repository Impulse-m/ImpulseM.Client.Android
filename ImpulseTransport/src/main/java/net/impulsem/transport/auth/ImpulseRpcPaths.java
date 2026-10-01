package net.impulsem.transport.auth;

import java.io.UnsupportedEncodingException;
import net.impulsem.transport.wire.ProtoReader;
import net.impulsem.transport.wire.ProtoWriter;


/** Paths and hand-encoded messages of the impulse.* services that have no TL counterpart. */
public final class ImpulseRpcPaths {

    public static final String RefreshSession = "/impulse.auth.AuthService/RefreshSession";
    public static final String GetCentrifugoToken = "/impulse.sync.SyncService/GetCentrifugoToken";
    public static final String GetChannelActorTags = "/impulse.sync.SyncService/GetChannelActorTags";


    /** The decoded impulse.auth.Authorization message; absent strings are null. */
    public static final class Authorization {

        public long userId;
        public String sessionToken;
        public String refreshToken;
    }


    private ImpulseRpcPaths() {
    }


    /** RefreshSessionRequest { string refresh_token = 1; } */
    public static byte[] encodeRefreshSession(String refreshToken) {
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytesField(1, utf8(refreshToken));
        return writer.toByteArray();
    }


    /** impulse.auth.Authorization { user_id = 1; session_token = 5; refresh_token = 6; }, other fields skipped. */
    public static Authorization decodeAuthorization(byte[] proto) {
        Authorization result = new Authorization();
        ProtoReader reader = new ProtoReader(proto, 0, proto.length);
        while (reader.next()) {
            int field = reader.field();
            int wire = reader.wireType();
            if (field == 1 && wire == 0) {
                result.userId = reader.readVarint();
            } else if (field == 5 && wire == 2) {
                result.sessionToken = string(reader.readBytes());
            } else if (field == 6 && wire == 2) {
                result.refreshToken = string(reader.readBytes());
            }
        }
        return result;
    }


    private static byte[] utf8(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }


    private static String string(byte[] value) {
        try {
            return new String(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
