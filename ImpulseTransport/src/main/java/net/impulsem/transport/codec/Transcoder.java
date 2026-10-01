package net.impulsem.transport.codec;

import net.impulsem.transport.schema.ConstructorSpec;
import net.impulsem.transport.schema.MethodSpec;
import net.impulsem.transport.schema.ParamSpec;
import net.impulsem.transport.schema.ResultSpec;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.schema.TypeSpec;
import net.impulsem.transport.wire.ProtoReader;
import net.impulsem.transport.wire.ProtoWriter;
import net.impulsem.transport.wire.TlReader;
import net.impulsem.transport.wire.TlWriter;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Schema-driven converter between Telegram TL binary and ImpulseM protobuf.
 * Instances are stateless and thread-safe.
 */
public final class Transcoder {

    private static final int BoolTrue = 0x997275b5;
    private static final int BoolFalse = 0xbc799737;
    private static final int VectorId = 0x1cb5c415;

    private static final int WireVarint = 0;
    private static final int WireFixed64 = 1;
    private static final int WireBytes = 2;
    private static final int WireFixed32 = 5;

    private static final int MaxDepth = 256;

    /** Wrapper methods of 229.json that are not in the mapping. Ids are the signed ids from that file. */
    private static final int InvokeAfterMsg = -878758099;
    private static final int InvokeAfterMsgs = 1036301552;
    private static final int InitConnection = -1043505495;
    private static final int InvokeWithLayer = -627372787;
    private static final int InvokeWithoutUpdates = -1080796745;
    private static final int InvokeWithMessagesRange = 911373810;
    private static final int InvokeWithTakeout = -1398145746;
    private static final int InvokeWithBusinessConnection = -584540274;
    private static final int InvokeWithGooglePlayIntegrity = 502868356;
    private static final int InvokeWithApnsSecret = 229528824;
    private static final int InvokeWithReCaptcha = -1380249708;

    private final TlProtoSchema schema;


    private static final class Occurrence {

        int wire;
        long value;
        int offset;
        int length;
    }


    private static final class Body {

        final byte[] data;
        final Map<Integer, List<Occurrence>> fields = new HashMap<Integer, List<Occurrence>>();


        Body(byte[] data) {
            this.data = data;
        }
    }


    public Transcoder(TlProtoSchema schema) {
        this.schema = schema;
    }


    public TlProtoSchema schema() {
        return schema;
    }


    public EncodedRequest encodeRequest(byte[] tl) {
        try {
            TlReader reader = new TlReader(tl, 0, tl.length);
            Long takeoutId = null;
            while (true) {
                int id = reader.readInt32();
                if (id == InvokeWithTakeout) {
                    takeoutId = Long.valueOf(reader.readInt64());
                } else if (!skipWrapper(id, reader)) {
                    MethodSpec method = schema.method(id);
                    if (method == null) {
                        throw new TranscodeException("unknown method id " + Integer.toHexString(id));
                    }
                    ProtoWriter writer = new ProtoWriter();
                    encodeParams(reader, method.params, writer, 0);
                    requireConsumed(reader);
                    return new EncodedRequest(method.id, method.path, writer.toByteArray(), takeoutId);
                }
            }
        } catch (TranscodeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TranscodeException("cannot encode request: " + e.getMessage(), e);
        }
    }


    public byte[] decodeResult(int methodId, byte[] proto, CaptureSink sink) {
        MethodSpec method = schema.method(methodId);
        if (method == null) {
            throw new TranscodeException("unknown method id " + Integer.toHexString(methodId));
        }
        try {
            return decodeResultSpec(method.result, proto, sink);
        } catch (TranscodeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TranscodeException("cannot decode result of " + method.method + ": " + e.getMessage(), e);
        }
    }


    byte[] decodeResultSpec(
        ResultSpec result,
        byte[] proto,
        CaptureSink sink
    ) {
        TlWriter writer = new TlWriter();
        if (result.kind.equals("bool")) {
            writer.writeInt32(BoolTrue);
        } else if (result.kind.equals("bool_overlay")) {
            List<Occurrence> values = parseBody(proto, 0, proto.length).fields.get(1);
            boolean value = true;
            if (values != null && !values.isEmpty()) {
                value = values.get(values.size() - 1).value != 0;
            }
            writer.writeInt32(value ? BoolTrue : BoolFalse);
        } else if (result.kind.equals("int")) {
            writer.writeInt32((int) scalarValue(parseBody(proto, 0, proto.length), 1));
        } else if (result.kind.equals("long")) {
            writer.writeInt64(scalarValue(parseBody(proto, 0, proto.length), 1));
        } else if (result.kind.equals("list")) {
            Body body = parseBody(proto, 0, proto.length);
            writeVector(writer, result.elem, body, body.fields.get(1), sink, 0);
        } else if (result.kind.equals("object")) {
            protoToObject(result.typeName, proto, 0, proto.length, writer, sink, 0);
        } else {
            throw new TranscodeException("unsupported result kind " + result.kind);
        }
        return writer.toByteArray();
    }


    public byte[] decodeUpdates(byte[] proto, CaptureSink sink) {
        return protoToTlObject("Updates", proto, sink);
    }


    public byte[] tlObjectToProto(String tlType, byte[] tl) {
        try {
            TlReader reader = new TlReader(tl, 0, tl.length);
            byte[] result = objectToProto(tlType, reader, 0);
            requireConsumed(reader);
            return result;
        } catch (TranscodeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TranscodeException("cannot encode " + tlType + ": " + e.getMessage(), e);
        }
    }


    public byte[] protoToTlObject(String tlType, byte[] proto, CaptureSink sink) {
        try {
            TlWriter writer = new TlWriter();
            protoToObject(tlType, proto, 0, proto.length, writer, sink, 0);
            return writer.toByteArray();
        } catch (TranscodeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TranscodeException("cannot decode " + tlType + ": " + e.getMessage(), e);
        }
    }


    public boolean hasMethod(int methodId) {
        return schema.method(methodId) != null;
    }


    /** Decodes a request message with the method's param walk and returns the TL of the bare method call. */
    byte[] decodeRequestForTest(int methodId, byte[] proto) {
        MethodSpec method = schema.method(methodId);
        if (method == null) {
            throw new TranscodeException("unknown method id " + Integer.toHexString(methodId));
        }
        try {
            TlWriter writer = new TlWriter();
            writer.writeInt32(method.id);
            decodeParams(method.params, null, null, parseBody(proto, 0, proto.length), writer, null, 0);
            return writer.toByteArray();
        } catch (TranscodeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TranscodeException("cannot decode request " + method.method + ": " + e.getMessage(), e);
        }
    }


    // ---------------------------------------------------------------- TL -> proto


    private boolean skipWrapper(int id, TlReader reader) {
        switch (id) {
            case InvokeWithLayer:
                reader.readInt32();
                return true;
            case InvokeWithoutUpdates:
                return true;
            case InvokeAfterMsg:
                reader.readInt64();
                return true;
            case InvokeAfterMsgs:
                expectVector(reader.readInt32());
                int count = reader.readInt32();
                for (int i = 0; i < count; i++) {
                    reader.readInt64();
                }
                return true;
            case InitConnection:
                int flags = reader.readInt32();
                reader.readInt32();
                for (int i = 0; i < 6; i++) {
                    reader.readBytes();
                }
                if ((flags & 1) != 0) {
                    objectToProto("InputClientProxy", reader, 0);
                }
                if ((flags & 2) != 0) {
                    objectToProto("JSONValue", reader, 0);
                }
                return true;
            case InvokeWithMessagesRange:
                objectToProto("MessageRange", reader, 0);
                return true;
            case InvokeWithBusinessConnection:
                reader.readBytes();
                return true;
            case InvokeWithGooglePlayIntegrity:
            case InvokeWithApnsSecret:
                reader.readBytes();
                reader.readBytes();
                return true;
            case InvokeWithReCaptcha:
                reader.readBytes();
                return true;
            default:
                return false;
        }
    }


    private byte[] objectToProto(
        String type,
        TlReader reader,
        int depth
    ) {
        checkDepth(depth);
        int id = reader.readInt32();
        ConstructorSpec constructor = schema.constructor(id);
        if (constructor == null) {
            throw new TranscodeException("unknown constructor " + Integer.toHexString(id) + " while reading " + type);
        }
        if (!constructor.type.equals(type)) {
            throw new TranscodeException("constructor " + constructor.predicate + " is " + constructor.type + ", expected " + type);
        }
        ProtoWriter body = new ProtoWriter();
        encodeParams(reader, constructor.params, body, depth);
        TypeSpec typeSpec = schema.type(type);
        if (typeSpec != null && typeSpec.polymorphic) {
            if (constructor.arm == null) {
                throw new TranscodeException("constructor " + constructor.predicate + " has no arm");
            }
            ProtoWriter wrapper = new ProtoWriter();
            wrapper.writeBytesField(constructor.arm.intValue(), body.toByteArray());
            return wrapper.toByteArray();
        }
        return body.toByteArray();
    }


    private void encodeParams(
        TlReader reader,
        List<ParamSpec> params,
        ProtoWriter writer,
        int depth
    ) {
        Map<String, Integer> registers = new HashMap<String, Integer>();
        for (ParamSpec param : params) {
            if (param.kind == ParamSpec.Kind.FLAGS) {
                registers.put(param.name, Integer.valueOf(reader.readInt32()));
                continue;
            }
            if (param.derivedFrom != null) {
                continue;
            }
            if (param.flagRegister != null) {
                Integer register = registers.get(param.flagRegister);
                if (register == null || (register.intValue() & (1 << param.flagBit)) == 0) {
                    continue;
                }
            }
            encodeValue(reader, param, writer, depth);
        }
    }


    private void encodeValue(
        TlReader reader,
        ParamSpec param,
        ProtoWriter writer,
        int depth
    ) {
        switch (param.kind) {
            case TRUE:
                writer.writeVarintField(param.field, 1);
                break;
            case BOOL:
                writer.writeVarintField(param.field, readBool(reader) ? 1 : 0);
                break;
            case INT:
                writer.writeVarintField(param.field, reader.readInt32());
                break;
            case LONG:
                writer.writeVarintField(param.field, reader.readInt64());
                break;
            case DOUBLE:
                writer.writeFixed64Field(param.field, reader.readInt64());
                break;
            case STRING:
            case BYTES:
                writer.writeBytesField(param.field, reader.readBytes());
                break;
            case INT128:
                writer.writeBytesField(param.field, reader.readRaw(16));
                break;
            case INT256:
                writer.writeBytesField(param.field, reader.readRaw(32));
                break;
            case OBJECT:
                writer.writeBytesField(param.field, objectToProto(param.typeName, reader, depth + 1));
                break;
            case VECTOR:
                encodeVector(reader, param, writer, depth);
                break;
            default:
                throw new TranscodeException("cannot encode param " + param.name + " of kind " + param.kind);
        }
    }


    private void encodeVector(
        TlReader reader,
        ParamSpec param,
        ProtoWriter writer,
        int depth
    ) {
        expectVector(reader.readInt32());
        int count = reader.readInt32();
        if (count < 0 || count > reader.remaining()) {
            throw new TranscodeException("bad vector length " + count + " in " + param.name);
        }
        if (count == 0) {
            return;
        }
        ParamSpec elem = param.elem;
        switch (elem.kind) {
            case INT: {
                long[] values = new long[count];
                for (int i = 0; i < count; i++) {
                    values[i] = reader.readInt32();
                }
                writer.writePackedVarints(param.field, values);
                break;
            }
            case LONG: {
                long[] values = new long[count];
                for (int i = 0; i < count; i++) {
                    values[i] = reader.readInt64();
                }
                writer.writePackedVarints(param.field, values);
                break;
            }
            case BOOL: {
                long[] values = new long[count];
                for (int i = 0; i < count; i++) {
                    values[i] = readBool(reader) ? 1 : 0;
                }
                writer.writePackedVarints(param.field, values);
                break;
            }
            case DOUBLE: {
                long[] values = new long[count];
                for (int i = 0; i < count; i++) {
                    values[i] = reader.readInt64();
                }
                writer.writePackedFixed64(param.field, values);
                break;
            }
            case STRING:
            case BYTES:
                for (int i = 0; i < count; i++) {
                    writer.writeBytesField(param.field, reader.readBytes());
                }
                break;
            case INT128:
                for (int i = 0; i < count; i++) {
                    writer.writeBytesField(param.field, reader.readRaw(16));
                }
                break;
            case INT256:
                for (int i = 0; i < count; i++) {
                    writer.writeBytesField(param.field, reader.readRaw(32));
                }
                break;
            case OBJECT:
                for (int i = 0; i < count; i++) {
                    writer.writeBytesField(param.field, objectToProto(elem.typeName, reader, depth + 1));
                }
                break;
            default:
                throw new TranscodeException("unsupported vector element " + elem.kind + " in " + param.name);
        }
    }


    // ---------------------------------------------------------------- proto -> TL


    private void protoToObject(
        String type,
        byte[] data,
        int offset,
        int length,
        TlWriter writer,
        CaptureSink sink,
        int depth
    ) {
        checkDepth(depth);
        TypeSpec typeSpec = schema.type(type);
        if (typeSpec == null) {
            throw new TranscodeException("unknown type " + type);
        }
        ConstructorSpec constructor = null;
        int bodyOffset = offset;
        int bodyLength = length;
        if (typeSpec.polymorphic) {
            ProtoReader reader = new ProtoReader(data, offset, length);
            while (reader.next()) {
                if (reader.wireType() == WireBytes && typeSpec.arms.containsKey(reader.field())) {
                    constructor = schema.constructorByArm(typeSpec, reader.field());
                    int[] bounds = reader.readLengthDelimitedBounds();
                    bodyOffset = bounds[0];
                    bodyLength = bounds[1];
                    break;
                }
            }
            if (constructor == null) {
                throw new TranscodeException("unset oneof for " + type);
            }
        } else {
            List<ConstructorSpec> constructors = schema.constructorsOf(type);
            if (constructors.size() != 1) {
                throw new TranscodeException("type " + type + " has " + constructors.size() + " constructors but is not polymorphic");
            }
            constructor = constructors.get(0);
        }
        Body body = parseBody(data, bodyOffset, bodyLength);
        writer.writeInt32(constructor.id);
        decodeParams(constructor.params, constructor.capture, constructor.proto, body, writer, sink, depth);
    }


    private void decodeParams(
        List<ParamSpec> params,
        List<ConstructorSpec.Capture> captures,
        String protoName,
        Body body,
        TlWriter writer,
        CaptureSink sink,
        int depth
    ) {
        Map<String, Integer> registers = new HashMap<String, Integer>();
        for (ParamSpec param : params) {
            if (param.kind == ParamSpec.Kind.FLAGS) {
                registers.put(param.name, Integer.valueOf(0));
            }
        }
        for (ParamSpec param : params) {
            if (param.flagRegister == null) {
                continue;
            }
            ParamSpec source = param;
            if (param.derivedFrom != null) {
                source = findParam(params, param.derivedFrom);
            }
            Integer register = registers.get(param.flagRegister);
            if (register == null) {
                throw new TranscodeException("flag register " + param.flagRegister + " is not declared");
            }
            // A flagged vector that is present but empty leaves its bit cleared (documented behaviour).
            if (isPresent(source, body)) {
                registers.put(param.flagRegister, Integer.valueOf(register.intValue() | (1 << param.flagBit)));
            }
        }
        if (captures != null && sink != null) {
            for (ConstructorSpec.Capture capture : captures) {
                List<Occurrence> occurrences = body.fields.get(capture.field);
                if (occurrences != null && !occurrences.isEmpty()) {
                    Occurrence last = occurrences.get(occurrences.size() - 1);
                    sink.onCapture(protoName, capture.name, utf8(body.data, last.offset, last.length));
                }
            }
        }
        for (ParamSpec param : params) {
            if (param.kind == ParamSpec.Kind.FLAGS) {
                writer.writeInt32(registers.get(param.name).intValue());
                continue;
            }
            if (param.derivedFrom != null) {
                continue;
            }
            if (param.flagRegister != null && (registers.get(param.flagRegister).intValue() & (1 << param.flagBit)) == 0) {
                continue;
            }
            decodeValue(param, body, writer, sink, depth);
        }
    }


    private void decodeValue(
        ParamSpec param,
        Body body,
        TlWriter writer,
        CaptureSink sink,
        int depth
    ) {
        List<Occurrence> occurrences = body.fields.get(param.field);
        Occurrence last = null;
        if (occurrences != null && !occurrences.isEmpty()) {
            last = occurrences.get(occurrences.size() - 1);
        }
        switch (param.kind) {
            case TRUE:
                break;
            case BOOL:
                writer.writeInt32(last != null && last.value != 0 ? BoolTrue : BoolFalse);
                break;
            case INT:
                writer.writeInt32(last == null ? 0 : (int) numeric(param, last));
                break;
            case LONG:
                writer.writeInt64(last == null ? 0L : numeric(param, last));
                break;
            case DOUBLE:
                if (last != null && last.wire != WireFixed64) {
                    throw new TranscodeException("field " + param.name + " must be fixed64");
                }
                writer.writeInt64(last == null ? 0L : last.value);
                break;
            case STRING:
            case BYTES:
                writer.writeBytes(last == null ? new byte[0] : bytes(param, body, last));
                break;
            case INT128:
                writer.writeRaw(fixedBytes(param, body, last, 16));
                break;
            case INT256:
                writer.writeRaw(fixedBytes(param, body, last, 32));
                break;
            case OBJECT:
                if (last != null) {
                    if (last.wire != WireBytes) {
                        throw new TranscodeException("field " + param.name + " must be length-delimited");
                    }
                    protoToObject(param.typeName, body.data, last.offset, last.length, writer, sink, depth + 1);
                } else {
                    writeEmptyObject(param, writer, sink, depth);
                }
                break;
            case VECTOR:
                writeVector(writer, param.elem, body, occurrences, sink, depth);
                break;
            default:
                throw new TranscodeException("cannot decode param " + param.name + " of kind " + param.kind);
        }
    }


    /**
     * An absent unconditional object: a flat type decodes from an empty body (all defaults),
     * a polymorphic type falls back to its zero-param constructor (preferring "...Empty").
     */
    private void writeEmptyObject(
        ParamSpec param,
        TlWriter writer,
        CaptureSink sink,
        int depth
    ) {
        TypeSpec typeSpec = schema.type(param.typeName);
        if (typeSpec != null && !typeSpec.polymorphic) {
            protoToObject(param.typeName, new byte[0], 0, 0, writer, sink, depth + 1);
            return;
        }
        ConstructorSpec fallback = null;
        for (ConstructorSpec candidate : schema.constructorsOf(param.typeName)) {
            if (!candidate.params.isEmpty()) {
                continue;
            }
            if (candidate.predicate.endsWith("Empty")) {
                fallback = candidate;
                break;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        if (fallback == null) {
            throw new TranscodeException("missing required object field " + param.name + " of type " + param.typeName);
        }
        writer.writeInt32(fallback.id);
    }


    private void writeVector(
        TlWriter writer,
        ParamSpec elem,
        Body body,
        List<Occurrence> occurrences,
        CaptureSink sink,
        int depth
    ) {
        List<Occurrence> list = occurrences;
        if (list == null) {
            list = new ArrayList<Occurrence>();
        }
        writer.writeInt32(VectorId);
        switch (elem.kind) {
            case INT:
            case LONG:
            case BOOL: {
                long[] values = packedVarints(elem, body, list);
                writer.writeInt32(values.length);
                for (long value : values) {
                    if (elem.kind == ParamSpec.Kind.INT) {
                        writer.writeInt32((int) value);
                    } else if (elem.kind == ParamSpec.Kind.LONG) {
                        writer.writeInt64(value);
                    } else {
                        writer.writeInt32(value != 0 ? BoolTrue : BoolFalse);
                    }
                }
                break;
            }
            case DOUBLE: {
                long[] values = packedFixed64(elem, body, list);
                writer.writeInt32(values.length);
                for (long value : values) {
                    writer.writeInt64(value);
                }
                break;
            }
            case STRING:
            case BYTES:
            case INT128:
            case INT256:
            case OBJECT: {
                writer.writeInt32(list.size());
                for (Occurrence occurrence : list) {
                    if (occurrence.wire != WireBytes) {
                        throw new TranscodeException("vector element must be length-delimited");
                    }
                    if (elem.kind == ParamSpec.Kind.OBJECT) {
                        protoToObject(elem.typeName, body.data, occurrence.offset, occurrence.length, writer, sink, depth + 1);
                    } else if (elem.kind == ParamSpec.Kind.INT128) {
                        writer.writeRaw(fixedBytes(elem, body, occurrence, 16));
                    } else if (elem.kind == ParamSpec.Kind.INT256) {
                        writer.writeRaw(fixedBytes(elem, body, occurrence, 32));
                    } else {
                        writer.writeBytes(Arrays.copyOfRange(body.data, occurrence.offset, occurrence.offset + occurrence.length));
                    }
                }
                break;
            }
            default:
                throw new TranscodeException("unsupported vector element " + elem.kind);
        }
    }


    private static boolean isPresent(
        ParamSpec param,
        Body body
    ) {
        List<Occurrence> occurrences = body.fields.get(param.field);
        if (occurrences == null || occurrences.isEmpty()) {
            return false;
        }
        if (param.kind == ParamSpec.Kind.VECTOR && isPackable(param.elem.kind)) {
            for (Occurrence occurrence : occurrences) {
                if (occurrence.wire != WireBytes || occurrence.length > 0) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }


    private static boolean isPackable(ParamSpec.Kind kind) {
        return kind == ParamSpec.Kind.INT
            || kind == ParamSpec.Kind.LONG
            || kind == ParamSpec.Kind.BOOL
            || kind == ParamSpec.Kind.DOUBLE;
    }


    private static ParamSpec findParam(
        List<ParamSpec> params,
        String name
    ) {
        for (ParamSpec candidate : params) {
            if (candidate.name.equals(name)) {
                return candidate;
            }
        }
        throw new TranscodeException("derived_from references unknown param " + name);
    }


    private static long[] packedVarints(
        ParamSpec elem,
        Body body,
        List<Occurrence> occurrences
    ) {
        List<Long> values = new ArrayList<Long>();
        for (Occurrence occurrence : occurrences) {
            if (occurrence.wire == WireBytes) {
                ProtoReader reader = new ProtoReader(body.data, occurrence.offset, occurrence.length);
                while (reader.hasMore()) {
                    values.add(Long.valueOf(reader.readVarint()));
                }
            } else if (occurrence.wire == WireVarint || occurrence.wire == WireFixed32) {
                values.add(Long.valueOf(occurrence.value));
            } else {
                throw new TranscodeException("bad wire type " + occurrence.wire + " for vector of " + elem.kind);
            }
        }
        long[] result = new long[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i).longValue();
        }
        return result;
    }


    private static long[] packedFixed64(
        ParamSpec elem,
        Body body,
        List<Occurrence> occurrences
    ) {
        List<Long> values = new ArrayList<Long>();
        for (Occurrence occurrence : occurrences) {
            if (occurrence.wire == WireBytes) {
                if (occurrence.length % 8 != 0) {
                    throw new TranscodeException("packed double blob has length " + occurrence.length);
                }
                for (int position = 0; position < occurrence.length; position += 8) {
                    long bits = 0;
                    for (int i = 7; i >= 0; i--) {
                        bits = (bits << 8) | (body.data[occurrence.offset + position + i] & 0xFFL);
                    }
                    values.add(Long.valueOf(bits));
                }
            } else if (occurrence.wire == WireFixed64) {
                values.add(Long.valueOf(occurrence.value));
            } else {
                throw new TranscodeException("bad wire type " + occurrence.wire + " for vector of " + elem.kind);
            }
        }
        long[] result = new long[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i).longValue();
        }
        return result;
    }


    private static long numeric(
        ParamSpec param,
        Occurrence occurrence
    ) {
        if (occurrence.wire != WireVarint && occurrence.wire != WireFixed32 && occurrence.wire != WireFixed64) {
            throw new TranscodeException("field " + param.name + " must be numeric");
        }
        return occurrence.value;
    }


    private static long scalarValue(
        Body body,
        int field
    ) {
        List<Occurrence> occurrences = body.fields.get(field);
        if (occurrences == null || occurrences.isEmpty()) {
            return 0L;
        }
        return occurrences.get(occurrences.size() - 1).value;
    }


    private static byte[] bytes(
        ParamSpec param,
        Body body,
        Occurrence occurrence
    ) {
        if (occurrence.wire != WireBytes) {
            throw new TranscodeException("field " + param.name + " must be length-delimited");
        }
        return Arrays.copyOfRange(body.data, occurrence.offset, occurrence.offset + occurrence.length);
    }


    private static byte[] fixedBytes(
        ParamSpec param,
        Body body,
        Occurrence occurrence,
        int expected
    ) {
        int actual = 0;
        if (occurrence != null) {
            if (occurrence.wire != WireBytes) {
                throw new TranscodeException("field " + param.name + " must be length-delimited");
            }
            actual = occurrence.length;
        }
        if (actual != expected) {
            throw new TranscodeException("field " + param.name + " must be " + expected + " bytes, got " + actual);
        }
        return Arrays.copyOfRange(body.data, occurrence.offset, occurrence.offset + occurrence.length);
    }


    private static Body parseBody(
        byte[] data,
        int offset,
        int length
    ) {
        Body body = new Body(data);
        ProtoReader reader = new ProtoReader(data, offset, length);
        while (reader.next()) {
            Occurrence occurrence = new Occurrence();
            occurrence.wire = reader.wireType();
            switch (occurrence.wire) {
                case WireVarint:
                    occurrence.value = reader.readVarint();
                    break;
                case WireFixed64:
                    occurrence.value = reader.readFixed64();
                    break;
                case WireFixed32:
                    occurrence.value = reader.readFixed32();
                    break;
                case WireBytes: {
                    int[] bounds = reader.readLengthDelimitedBounds();
                    occurrence.offset = bounds[0];
                    occurrence.length = bounds[1];
                    break;
                }
                default:
                    throw new TranscodeException("unsupported protobuf wire type " + occurrence.wire);
            }
            List<Occurrence> list = body.fields.get(reader.field());
            if (list == null) {
                list = new ArrayList<Occurrence>(1);
                body.fields.put(reader.field(), list);
            }
            list.add(occurrence);
        }
        return body;
    }


    // ---------------------------------------------------------------- helpers


    private static boolean readBool(TlReader reader) {
        int id = reader.readInt32();
        if (id == BoolTrue) {
            return true;
        }
        if (id == BoolFalse) {
            return false;
        }
        throw new TranscodeException("bad Bool id " + Integer.toHexString(id));
    }


    private static void expectVector(int id) {
        if (id != VectorId) {
            throw new TranscodeException("expected vector id, got " + Integer.toHexString(id));
        }
    }


    private static void requireConsumed(TlReader reader) {
        if (reader.remaining() != 0) {
            throw new TranscodeException(reader.remaining() + " trailing bytes after TL object");
        }
    }


    private static void checkDepth(int depth) {
        if (depth > MaxDepth) {
            throw new TranscodeException("nesting too deep");
        }
    }


    private static String utf8(
        byte[] data,
        int offset,
        int length
    ) {
        try {
            return new String(data, offset, length, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
