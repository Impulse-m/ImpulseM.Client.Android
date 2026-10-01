package net.impulsem.transport.rpc;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.codec.CaptureSink;
import net.impulsem.transport.codec.EncodedRequest;
import net.impulsem.transport.codec.TranscodeException;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.errors.AuthErrorClass;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.errors.RpcErrors;
import net.impulsem.transport.grpcweb.GrpcWebClient;
import net.impulsem.transport.grpcweb.GrpcWebResponse;
import net.impulsem.transport.schema.ConstructorSpec;
import net.impulsem.transport.schema.MethodSpec;
import net.impulsem.transport.schema.ParamSpec;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.schema.TypeSpec;
import net.impulsem.transport.wire.ProtoReader;
import okhttp3.Call;
import okhttp3.Request;


/** Runs one TL call over gRPC-Web: transcode, authenticate, map errors, refresh and retry, capture login tokens. */
public final class RpcClient {

    private static final Logger Log = Logger.getLogger(RpcClient.class.getName());

    private static final String HeaderAuthorization = "authorization";
    private static final String HeaderQrTicket = "x-impulse-qr-exporter-ticket";
    private static final String HeaderTakeout = "x-takeout-id";
    private static final String MetadataPendingToken = "pending-token";

    private static final String PredicateAuthorization = "auth.authorization";
    private static final String PredicateSentCodeSuccess = "auth.sentCodeSuccess";
    private static final String PredicateLoginTokenSuccess = "auth.loginTokenSuccess";
    private static final String PredicateUser = "user";
    private static final String PredicateUserEmpty = "userEmpty";

    private final Transcoder transcoder;
    private final GrpcWebClient grpc;
    private final TokenManager tokens;
    private final TlProtoSchema schema;


    public RpcClient(
        Transcoder transcoder,
        GrpcWebClient grpc,
        TokenManager tokens
    ) {
        this.transcoder = transcoder;
        this.grpc = grpc;
        this.tokens = tokens;
        this.schema = transcoder.schema();
    }


    /**
     * Encodes the request and builds the HTTP call. The returned handle forwards cancel() to the
     * attempt in flight, and its tag carries the EncodedRequest so {@link #execute(Call)} can
     * rebuild attempts with fresh credentials.
     *
     * @throws TranscodeException when the method is not in the mapping or the TL is malformed.
     */
    public Call prepare(byte[] tlRequest) {
        EncodedRequest encoded = transcoder.encodeRequest(tlRequest);
        return new ActiveCall(encoded, build(encoded));
    }


    /**
     * One attempt, with one transparent refresh and retry on REFRESH_AND_RETRY. When another caller
     * has already refreshed (the bearer changed since the failed attempt) the refresh is skipped.
     *
     * <p>On a force-logout error the outcome carries {@code forceLogout = true}, but this client does
     * NOT clear the stored session; the Android layer does that.
     *
     * @throws IOException with message "Canceled" when the call was cancelled, or on transport failure.
     */
    public RpcOutcome execute(Call call) throws IOException {
        ActiveCall active;
        if (call instanceof ActiveCall) {
            active = (ActiveCall) call;
        } else {
            Object tag = call.request().tag();
            if (!(tag instanceof EncodedRequest)) {
                throw new IllegalArgumentException("call was not created by RpcClient.prepare");
            }
            active = new ActiveCall((EncodedRequest) tag, call);
        }
        EncodedRequest encoded = active.encoded;

        checkCanceled(active);
        if (tokens.needsProactiveRefresh() && !tokens.refreshBlocking()) {
            return new RpcOutcome(null, new RpcError(401, "SESSION_EXPIRED"), true);
        }
        checkCanceled(active);
        Call firstCall = credentialsCurrent(active.request()) ? active.current() : build(encoded);
        String sentAuthorization = firstCall.request().header(HeaderAuthorization);

        RpcOutcome first = attemptOnce(active, firstCall, encoded);
        if (first.error == null) {
            return first;
        }
        AuthErrorClass errorClass = RpcErrors.classify(first.error);
        if (errorClass == AuthErrorClass.FORCE_LOGOUT) {
            return new RpcOutcome(null, first.error, true);
        }
        if (errorClass != AuthErrorClass.REFRESH_AND_RETRY) {
            return first;
        }
        if (!sessionRefreshed(sentAuthorization)) {
            return new RpcOutcome(null, first.error, true);
        }
        checkCanceled(active);
        RpcOutcome second = attemptOnce(active, build(encoded), encoded);
        if (second.error != null) {
            AuthErrorClass secondClass = RpcErrors.classify(second.error);
            // Still unregistered right after a refresh: the session is dead, refreshing again only burns the rotating token.
            if (secondClass == AuthErrorClass.FORCE_LOGOUT || secondClass == AuthErrorClass.REFRESH_AND_RETRY) {
                return new RpcOutcome(null, second.error, true);
            }
        }
        return second;
    }


    public RpcOutcome callBlocking(byte[] tlRequest) throws IOException {
        Call call;
        try {
            call = prepare(tlRequest);
        } catch (TranscodeException e) {
            return new RpcOutcome(null, new RpcError(400, "METHOD_INVALID"), false);
        }
        return execute(call);
    }


    /**
     * Calls an impulse.* rpc that has no TL counterpart and returns the response message.
     *
     * @throws SessionLostException when the session is unrecoverable.
     * @throws IOException on transport failures and on any other error status.
     */
    public byte[] callImpulse(
        String path,
        byte[] protoRequest
    ) throws IOException {
        if (tokens.needsProactiveRefresh() && !tokens.refreshBlocking()) {
            throw new SessionLostException(path + " failed: session expired before the call", new RpcError(401, "SESSION_EXPIRED"));
        }
        boolean retried = false;
        while (true) {
            Call call = grpc.newCall(path, protoRequest, headers(null));
            String sentAuthorization = call.request().header(HeaderAuthorization);
            GrpcWebResponse response = grpc.execute(call);
            absorbMetadata(response);
            RpcError error = RpcErrors.fromResponse(response);
            if (error == null) {
                return response.body;
            }
            AuthErrorClass errorClass = RpcErrors.classify(error);
            if (errorClass == AuthErrorClass.FORCE_LOGOUT) {
                throw new SessionLostException(path + " failed: " + error, error);
            }
            if (errorClass == AuthErrorClass.REFRESH_AND_RETRY) {
                if (retried || !sessionRefreshed(sentAuthorization)) {
                    throw new SessionLostException(path + " failed: " + error, error);
                }
                retried = true;
                continue;
            }
            throw new IOException(path + " failed: " + error);
        }
    }


    /**
     * True when a usable new bearer is in place: either another caller already replaced the one the
     * failed attempt sent, or our own refresh succeeded.
     */
    private boolean sessionRefreshed(String sentAuthorization) throws IOException {
        String current = tokens.bearer();
        if (current != null && !("Bearer " + current).equals(sentAuthorization)) {
            return true;
        }
        return tokens.refreshBlocking();
    }


    private static void checkCanceled(ActiveCall active) throws IOException {
        if (active.isCanceled()) {
            throw new IOException("Canceled");
        }
    }


    private Call build(EncodedRequest encoded) {
        return grpc.newCall(encoded.path, encoded.proto, headers(encoded), encoded);
    }


    private Map<String, String> headers(EncodedRequest encoded) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String bearer = tokens.bearer();
        if (bearer != null) {
            headers.put(HeaderAuthorization, "Bearer " + bearer);
        }
        String ticket = tokens.qrExporterTicket();
        if (ticket != null) {
            headers.put(HeaderQrTicket, ticket);
        }
        if (encoded != null && encoded.takeoutId != null) {
            headers.put(HeaderTakeout, String.valueOf(encoded.takeoutId));
        }
        return headers;
    }


    private boolean credentialsCurrent(Request request) {
        String bearer = tokens.bearer();
        String expectedAuthorization = bearer == null ? null : "Bearer " + bearer;
        return equal(expectedAuthorization, request.header(HeaderAuthorization))
            && equal(tokens.qrExporterTicket(), request.header(HeaderQrTicket));
    }


    private static boolean equal(
        String a,
        String b
    ) {
        return a == null ? b == null : a.equals(b);
    }


    private RpcOutcome attemptOnce(
        ActiveCall active,
        Call call,
        EncodedRequest encoded
    ) throws IOException {
        active.attach(call);
        GrpcWebResponse response;
        try {
            response = grpc.execute(call);
        } catch (IOException e) {
            if (active.isCanceled()) {
                // OkHttp reports a mid-flight cancel as "Socket closed" or "Canceled" depending on timing.
                throw new IOException("Canceled", e);
            }
            throw e;
        }
        absorbMetadata(response);
        RpcError error = RpcErrors.fromResponse(response);
        if (error != null) {
            return new RpcOutcome(null, error, false);
        }

        final String[] captured = new String[2];
        byte[] tl;
        try {
            tl = transcoder.decodeResult(
                encoded.methodId,
                response.body,
                new CaptureSink() {
                    @Override
                    public void onCapture(
                        String protoMessage,
                        String fieldName,
                        String value
                    ) {
                        if ("session_token".equals(fieldName)) {
                            captured[0] = value;
                        } else if ("refresh_token".equals(fieldName)) {
                            captured[1] = value;
                        }
                    }
                }
            );
        } catch (TranscodeException e) {
            // The server already executed the call, so a retry would repeat it; deliver once.
            Log.log(Level.WARNING, "cannot decode response of " + encoded.path + ": " + e.getMessage(), e);
            return new RpcOutcome(null, new RpcError(400, "TRANSCODE_FAILED"), false);
        }
        if (captured[0] != null && captured[1] != null) {
            tokens.onLoginTokens(captured[0], captured[1], extractUserId(encoded.methodId, response.body));
        }
        return new RpcOutcome(tl, null, false);
    }


    private void absorbMetadata(GrpcWebResponse response) {
        Map<String, String> metadata = response.metadata;
        if (metadata == null) {
            return;
        }
        String pending = metadata.get(MetadataPendingToken);
        if (pending != null && !pending.isEmpty()) {
            tokens.setPendingToken(pending);
        }
        String ticket = metadata.get(HeaderQrTicket);
        if (ticket != null && !ticket.isEmpty()) {
            tokens.setQrExporterTicket(ticket);
        }
    }


    // ---------------------------------------------------------------- user id lookup

    /**
     * Walks the response proto to the authorization's user and reads its id. All field and arm
     * numbers come from the schema: auth.authorization.user, the arm of the user constructor and
     * that constructor's id param. Returns 0 when the response has no user.
     */
    private long extractUserId(
        int methodId,
        byte[] proto
    ) {
        try {
            MethodSpec method = schema.method(methodId);
            if (method == null || method.result == null || method.result.typeName == null) {
                return 0;
            }
            String type = method.result.typeName;
            byte[] authorization;
            if (type.equals(typeOf(PredicateAuthorization))) {
                authorization = proto;
            } else if (type.equals(typeOf(PredicateSentCodeSuccess))) {
                authorization = wrapped(proto, type, PredicateSentCodeSuccess);
            } else if (type.equals(typeOf(PredicateLoginTokenSuccess))) {
                authorization = wrapped(proto, type, PredicateLoginTokenSuccess);
            } else {
                return 0;
            }
            if (authorization == null) {
                return 0;
            }
            return userIdOfAuthorization(authorization);
        } catch (RuntimeException e) {
            return 0;
        }
    }


    private byte[] wrapped(
        byte[] proto,
        String typeName,
        String predicate
    ) {
        ConstructorSpec success = constructor(predicate);
        byte[] body = armBody(proto, schema.type(typeName), success.arm);
        if (body == null) {
            return null;
        }
        ParamSpec param = param(success, "authorization");
        return bytesField(body, param.field);
    }


    private long userIdOfAuthorization(byte[] authorization) {
        ConstructorSpec value = constructor(PredicateAuthorization);
        byte[] valueBody = armBody(authorization, schema.type(value.type), value.arm);
        if (valueBody == null) {
            return 0;
        }
        ParamSpec userParam = param(value, "user");
        byte[] user = bytesField(valueBody, userParam.field);
        if (user == null) {
            return 0;
        }
        TypeSpec userType = schema.type(userParam.typeName);
        ConstructorSpec[] candidates = {constructor(PredicateUser), constructor(PredicateUserEmpty)};
        for (ConstructorSpec candidate : candidates) {
            byte[] body = armBody(user, userType, candidate.arm);
            if (body != null) {
                return varintField(body, param(candidate, "id").field);
            }
        }
        return 0;
    }


    private String typeOf(String predicate) {
        return constructor(predicate).type;
    }


    private ConstructorSpec constructor(String predicate) {
        for (ConstructorSpec spec : schema.constructors()) {
            if (predicate.equals(spec.predicate)) {
                return spec;
            }
        }
        throw new IllegalStateException("schema has no constructor " + predicate);
    }


    private static ParamSpec param(
        ConstructorSpec spec,
        String name
    ) {
        for (ParamSpec param : spec.params) {
            if (name.equals(param.name)) {
                return param;
            }
        }
        throw new IllegalStateException("constructor " + spec.predicate + " has no param " + name);
    }


    /** The message body of a polymorphic oneof arm, or null when another arm is set. */
    private static byte[] armBody(
        byte[] proto,
        TypeSpec type,
        Integer arm
    ) {
        if (!type.polymorphic) {
            return proto;
        }
        return arm == null ? null : bytesField(proto, arm.intValue());
    }


    private static byte[] bytesField(
        byte[] proto,
        int field
    ) {
        byte[] found = null;
        ProtoReader reader = new ProtoReader(proto, 0, proto.length);
        while (reader.next()) {
            if (reader.field() == field && reader.wireType() == 2) {
                found = reader.readBytes();
            }
        }
        return found;
    }


    private static long varintField(
        byte[] proto,
        int field
    ) {
        long found = 0;
        ProtoReader reader = new ProtoReader(proto, 0, proto.length);
        while (reader.next()) {
            if (reader.field() == field && reader.wireType() == 0) {
                found = reader.readVarint();
            }
        }
        return found;
    }
}
