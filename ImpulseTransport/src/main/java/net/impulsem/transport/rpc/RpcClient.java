package net.impulsem.transport.rpc;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.impulsem.transport.auth.Credentials;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.codec.CaptureSink;
import net.impulsem.transport.codec.EncodedRequest;
import net.impulsem.transport.codec.TranscodeException;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.errors.AuthErrorClass;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.errors.RpcErrors;
import net.impulsem.transport.schema.ConstructorSpec;
import net.impulsem.transport.schema.MethodSpec;
import net.impulsem.transport.schema.ParamSpec;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.schema.TypeSpec;
import net.impulsem.transport.wire.ProtoReader;


/** Runs one TL call over a Centrifugo carrier: transcode, authenticate, map errors, refresh and retry, capture login tokens. */
public final class RpcClient {

    static final long CallTimeoutMillis = 30_000L;
    static final long MediaCallTimeoutMillis = 10_000L;
    static final long DeclareWaitMillis = 10_000L;

    private static final Logger Log = Logger.getLogger(RpcClient.class.getName());

    private static final String ConnectionNotInitedText = "CONNECTION_NOT_INITED";

    private static final Set<String> MediaMethods = Collections.unmodifiableSet(
        new HashSet<String>(Arrays.asList(
            "upload.saveFilePart",
            "upload.saveBigFilePart",
            "upload.getFile",
            "upload.getWebFile"
        ))
    );

    private static final String PredicateAuthorization = "auth.authorization";
    private static final String PredicateSentCodeSuccess = "auth.sentCodeSuccess";
    private static final String PredicateLoginTokenSuccess = "auth.loginTokenSuccess";
    private static final String PredicateUser = "user";
    private static final String PredicateUserEmpty = "userEmpty";


    /** The connection's initConnection state, as ConnectionDeclarer keeps it. */
    public interface DeclarationGate {

        /** Waits until the current credential is declared, at most timeoutMillis; true when it is. */
        boolean awaitDeclared(long timeoutMillis) throws IOException;

        /** The server lost the declaration (CONNECTION_NOT_INITED): declare again. */
        void invalidate();
    }


    /**
     * One attempt's outcome, with its error already classified (a token refusal counts as REFRESH_AND_RETRY).
     * tokenRefused tells a refused token apart from other REFRESH_AND_RETRY errors such as AUTH_KEY_UNREGISTERED.
     */
    private static final class Attempt {

        final RpcOutcome outcome;
        final AuthErrorClass errorClass;
        final boolean tokenRefused;


        Attempt(
            RpcOutcome outcome,
            AuthErrorClass errorClass,
            boolean tokenRefused
        ) {
            this.outcome = outcome;
            this.errorClass = errorClass;
            this.tokenRefused = tokenRefused;
        }
    }


    private final Transcoder transcoder;
    private final RpcCarrier updateSocket;
    private final RpcCarrier mediaFleet;
    private final TokenManager tokens;
    private final Credentials credentials;
    private final TlProtoSchema schema;
    private volatile DeclarationGate declarationGate;


    /** The media fleet may be null until it exists; file calls then fail as a carrier fault. */
    public RpcClient(
        Transcoder transcoder,
        RpcCarrier updateSocket,
        RpcCarrier mediaFleet,
        TokenManager tokens,
        Credentials credentials
    ) {
        this.transcoder = transcoder;
        this.updateSocket = updateSocket;
        this.mediaFleet = mediaFleet;
        this.tokens = tokens;
        this.credentials = credentials;
        this.schema = transcoder.schema();
    }


    /** Sets the gate that holds calls until the connection is declared; null removes it. */
    public void setDeclarationGate(DeclarationGate gate) {
        declarationGate = gate;
    }


    /**
     * Encodes the request for the carriers. The returned handle carries the encoded request and forwards
     * cancel() to the carrier call in flight.
     *
     * @throws TranscodeException when the method is not in the mapping or the TL is malformed.
     */
    public PreparedCall prepare(byte[] tlRequest) {
        return new PreparedCall(transcoder.encodeRequest(tlRequest));
    }


    /**
     * One attempt, with one transparent refresh and retry on REFRESH_AND_RETRY. When another caller
     * has already refreshed (the bearer changed since the attempt began) the refresh is skipped.
     *
     * <p>On a force-logout error the outcome carries {@code forceLogout = true}, but this client does
     * NOT clear the stored session; the Android layer does that.
     *
     * @throws IOException with message "Canceled" when the call was cancelled, or on a carrier failure
     *     that is not an answer from the server.
     */
    public RpcOutcome execute(PreparedCall call) throws IOException {
        EncodedRequest encoded = call.encoded;
        checkCanceled(call);
        if (encoded.takeoutId != null) {
            // Takeout has no carrier on the fleets; the feature is hidden in the app.
            return new RpcOutcome(null, new RpcError(400, "TAKEOUT_INVALID"), false);
        }
        boolean signedIn = credentials.signedIn();
        // The server answers only the methods that suit the socket's credential, so a refusal here costs no round trip.
        if (!MethodClasses.allowed(signedIn, encoded.method)) {
            String socketKind = signedIn ? "session" : "identity";
            Log.log(Level.WARNING, "refused " + encoded.method + " on the " + socketKind + " socket");
            return new RpcOutcome(null, new RpcError(400, "METHOD_INVALID"), false);
        }
        if (signedIn && tokens.needsProactiveRefresh() && !tokens.refreshBlocking()) {
            return new RpcOutcome(null, new RpcError(401, "SESSION_EXPIRED"), true);
        }
        checkCanceled(call);
        DeclarationGate gate = declarationGate;
        if (gate != null) {
            // The result is ignored: when the declaration really is missing the server says CONNECTION_NOT_INITED.
            gate.awaitDeclared(DeclareWaitMillis);
            checkCanceled(call);
        }
        String credentialBefore = credentials.current();
        boolean[] redeclared = new boolean[1];
        Attempt first = attemptDeclaring(call, gate, redeclared);
        if (first.errorClass == AuthErrorClass.FORCE_LOGOUT) {
            return new RpcOutcome(null, first.outcome.error, true);
        }
        if (first.errorClass != AuthErrorClass.REFRESH_AND_RETRY) {
            return first.outcome;
        }
        if (!signedIn) {
            // G10: a refused identity is discarded; the socket reconnects with a new one and the SDK queues the retry.
            credentials.refused(credentialBefore);
            checkCanceled(call);
            Attempt retry = attemptDeclaring(call, gate, redeclared);
            if (retry.errorClass == AuthErrorClass.REFRESH_AND_RETRY || retry.errorClass == AuthErrorClass.FORCE_LOGOUT) {
                // There is no session to log out, so the refusal is not a forced logout.
                return new RpcOutcome(null, retry.outcome.error, false);
            }
            return retry.outcome;
        }
        if (first.tokenRefused) {
            credentials.refused(credentialBefore);
        }
        if (!sessionRefreshed(credentialBefore)) {
            return new RpcOutcome(null, first.outcome.error, true);
        }
        checkCanceled(call);
        Attempt second = attemptDeclaring(call, gate, redeclared);
        // Still unregistered right after a refresh: the session is dead, refreshing again only burns the rotating token.
        if (second.errorClass == AuthErrorClass.FORCE_LOGOUT || second.errorClass == AuthErrorClass.REFRESH_AND_RETRY) {
            return new RpcOutcome(null, second.outcome.error, true);
        }
        return second.outcome;
    }


    public RpcOutcome callBlocking(byte[] tlRequest) throws IOException {
        PreparedCall call;
        try {
            call = prepare(tlRequest);
        } catch (TranscodeException e) {
            return new RpcOutcome(null, new RpcError(400, "METHOD_INVALID"), false);
        }
        return execute(call);
    }


    /**
     * Calls an impulse.* registry method that has no TL counterpart and returns the reply bytes.
     *
     * @throws SessionLostException when the session is unrecoverable.
     * @throws RpcStatusException on any other error answer.
     * @throws IOException on a carrier failure that is not an answer from the server.
     */
    public byte[] callNamed(
        String method,
        byte[] protoRequest
    ) throws IOException {
        if (tokens.needsProactiveRefresh() && !tokens.refreshBlocking()) {
            throw new SessionLostException(method + " failed: session expired before the call", new RpcError(401, "SESSION_EXPIRED"));
        }
        boolean retried = false;
        while (true) {
            String bearerBefore = tokens.bearer();
            BlockingCall.Outcome settled = BlockingCall.send(updateSocket, method, protoRequest, CallTimeoutMillis, null);
            if (settled.failure == null) {
                return settled.reply;
            }
            Attempt attempt = failedAttempt(method, settled.failure);
            RpcError error = attempt.outcome.error;
            if (attempt.errorClass == AuthErrorClass.FORCE_LOGOUT) {
                throw new SessionLostException(method + " failed: " + error, error);
            }
            if (attempt.errorClass == AuthErrorClass.REFRESH_AND_RETRY) {
                if (retried || !sessionRefreshed(bearerBefore)) {
                    throw new SessionLostException(method + " failed: " + error, error);
                }
                retried = true;
                continue;
            }
            throw new RpcStatusException(method + " failed: " + error, error);
        }
    }


    /**
     * True when a usable new bearer is in place: either another caller already replaced the bearer the
     * failed attempt was sent with, or our own refresh succeeded.
     */
    private boolean sessionRefreshed(String bearerBefore) throws IOException {
        String current = tokens.bearer();
        if (current != null && !current.equals(bearerBefore)) {
            return true;
        }
        return tokens.refreshBlocking();
    }


    private static void checkCanceled(PreparedCall call) throws IOException {
        if (call.isCanceled()) {
            throw new IOException("Canceled");
        }
    }


    /**
     * One attempt that, when the server says the connection was never initialised, declares it again and attempts
     * once more. redeclared is shared by all attempts of one execute, so the declaration is repeated at most once.
     */
    private Attempt attemptDeclaring(
        PreparedCall call,
        DeclarationGate gate,
        boolean[] redeclared
    ) throws IOException {
        Attempt attempt = attemptOnce(call);
        if (gate == null || redeclared[0] || attempt.outcome.error == null) {
            return attempt;
        }
        if (!ConnectionNotInitedText.equals(attempt.outcome.error.text)) {
            return attempt;
        }
        redeclared[0] = true;
        gate.invalidate();
        gate.awaitDeclared(DeclareWaitMillis);
        checkCanceled(call);
        return attemptOnce(call);
    }


    private Attempt attemptOnce(PreparedCall call) throws IOException {
        EncodedRequest encoded = call.encoded;
        String method = encoded.method;
        RpcCarrier carrier = updateSocket;
        long timeoutMillis = CallTimeoutMillis;
        if (MediaMethods.contains(method)) {
            carrier = mediaFleet;
            timeoutMillis = MediaCallTimeoutMillis;
            if (carrier == null) {
                throw new IOException("no media fleet for " + method);
            }
        }
        BlockingCall.Outcome settled = BlockingCall.send(
            carrier,
            method,
            encoded.proto,
            timeoutMillis,
            new BlockingCall.SentSink() {
                @Override
                public void onSent(CarrierCall carrierCall) {
                    call.attach(carrierCall);
                }
            }
        );
        if (settled.failure != null) {
            if (call.isCanceled()) {
                throw new IOException("Canceled");
            }
            return failedAttempt(method, settled.failure);
        }
        return new Attempt(decodeReply(encoded, settled.reply), AuthErrorClass.NONE, false);
    }


    /**
     * Classifies a failed carrier call. A token refusal (103 or 109) is a refresh-and-retry; a failure that maps to no
     * Telegram error is the carrier's own fault, so it is thrown for the caller's network-failure path.
     */
    private static Attempt failedAttempt(
        String method,
        CarrierFailure failure
    ) throws IOException {
        RpcError error = CarrierErrors.toRpcError(failure);
        if (CarrierErrors.isTokenRefusal(failure)) {
            return new Attempt(new RpcOutcome(null, error, false), AuthErrorClass.REFRESH_AND_RETRY, true);
        }
        if (error == null) {
            throw new IOException("carrier fault on " + method + ": " + failure.cause, failure.cause);
        }
        return new Attempt(new RpcOutcome(null, error, false), RpcErrors.classify(error), false);
    }


    private RpcOutcome decodeReply(
        EncodedRequest encoded,
        byte[] body
    ) {
        final String[] captured = new String[2];
        byte[] tl;
        try {
            tl = transcoder.decodeResult(
                encoded.methodId,
                body,
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
            Log.log(Level.WARNING, "cannot decode response of " + encoded.method + ": " + e.getMessage(), e);
            return transcodeFailed(encoded, e.getMessage());
        }
        if (captured[0] != null && captured[1] != null) {
            long userId = extractUserId(encoded.methodId, body);
            if (userId == 0) {
                Log.log(Level.WARNING, "authorization from " + encoded.method + " carries no user id");
                return transcodeFailed(encoded, "authorization carries no user id");
            }
            tokens.onLoginTokens(captured[0], captured[1], userId);
        }
        return new RpcOutcome(tl, null, false);
    }


    private static RpcOutcome transcodeFailed(
        EncodedRequest encoded,
        String reason
    ) {
        return new RpcOutcome(null, new RpcError(400, "TRANSCODE_FAILED"), false, encoded.method + ": " + reason);
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
