package net.impulsem.transport.auth;

import java.io.UnsupportedEncodingException;
import net.impulsem.transport.wire.ProtoReader;
import net.impulsem.transport.wire.ProtoWriter;


/** Paths and hand-encoded messages of the impulse.* services that have no TL counterpart. */
public final class ImpulseRpcPaths {

    public static final String RefreshSession = "/impulse.auth.AuthService/RefreshSession";
    public static final String GetCentrifugoToken = "/impulse.sync.SyncService/GetCentrifugoToken";
    public static final String InitConnection = "/impulse.auth.AuthService/InitConnection";


    /** The decoded impulse.auth.RefreshSessionResponse message; absent strings are null. */
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


    /**
     * InitConnectionRequest { ConnectionDeclaration declaration = 1; }
     * ConnectionDeclaration { int32 api_id = 1; string device_model = 2; string system_version = 3;
     * string app_version = 4; string system_lang_code = 5; string lang_pack = 6; string lang_code = 7; };
     * proxy (8) and params (9) stay unset and empty values are omitted, as proto3 does.
     */
    public static byte[] encodeInitConnection(ConnectionDeclaration declaration) {
        ProtoWriter inner = new ProtoWriter();
        if (declaration.apiId != 0) {
            inner.writeVarintField(1, declaration.apiId);
        }
        writeString(inner, 2, declaration.deviceModel);
        writeString(inner, 3, declaration.systemVersion);
        writeString(inner, 4, declaration.appVersion);
        writeString(inner, 5, declaration.systemLangCode);
        writeString(inner, 6, declaration.langPack);
        writeString(inner, 7, declaration.langCode);
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytesField(1, inner.toByteArray());
        return writer.toByteArray();
    }


    private static void writeString(
        ProtoWriter writer,
        int field,
        String value
    ) {
        if (!value.isEmpty()) {
            writer.writeBytesField(field, utf8(value));
        }
    }


    /**
     * impulse.auth.RefreshSessionResponse { user_id = 1; session_token = 2; refresh_token = 3; grace = 4; }, the
     * answer of RefreshSession since the backend's 2026-10-16 change; other fields skipped.
     */
    public static Authorization decodeRefreshSession(byte[] proto) {
        Authorization result = new Authorization();
        ProtoReader reader = new ProtoReader(proto, 0, proto.length);
        while (reader.next()) {
            int field = reader.field();
            int wire = reader.wireType();
            if (field == 1 && wire == 0) {
                result.userId = reader.readVarint();
            } else if (field == 2 && wire == 2) {
                result.sessionToken = string(reader.readBytes());
            } else if (field == 3 && wire == 2) {
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
