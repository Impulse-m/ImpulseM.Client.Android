package net.impulsem.transport.live;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.impulsem.transport.schema.ConstructorSpec;
import net.impulsem.transport.schema.MethodSpec;
import net.impulsem.transport.schema.ParamSpec;
import net.impulsem.transport.schema.ResultSpec;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.TlReader;


/**
 * Minimal schema-driven TL decoder for assertions. Objects become maps with the constructor name
 * under "_"; vectors become lists, bool becomes Boolean, int/long become Integer/Long, bytes and
 * int128/int256 become byte[].
 */
public final class TlView {

    private static final int BoolTrue = 0x997275b5;
    private static final int BoolFalse = 0xbc799737;
    private static final int VectorId = 0x1cb5c415;

    private final TlProtoSchema schema;
    private final Map<Integer, ConstructorSpec> byId = new HashMap<Integer, ConstructorSpec>();


    public TlView(TlProtoSchema schema) {
        this.schema = schema;
        for (ConstructorSpec spec : schema.constructors()) {
            byId.put(spec.id, spec);
        }
    }


    /** Decodes the TL result of the method with the given name. */
    public Object result(
        String method,
        byte[] tl
    ) {
        MethodSpec spec = null;
        for (MethodSpec candidate : schema.methods()) {
            if (candidate.method.equals(method)) {
                spec = candidate;
            }
        }
        if (spec == null) {
            throw new IllegalArgumentException("unknown method " + method);
        }
        ResultSpec result = spec.result;
        TlReader reader = new TlReader(tl, 0, tl.length);
        Object value;
        if ("list".equals(result.kind)) {
            value = readVector(reader, result.elem);
        } else if ("bool".equals(result.kind) || "bool_overlay".equals(result.kind)) {
            value = readBool(reader);
        } else {
            value = readObject(reader);
        }
        if (reader.remaining() != 0) {
            throw new IllegalStateException(method + ": " + reader.remaining() + " trailing bytes");
        }
        return value;
    }


    @SuppressWarnings("unchecked")
    public Map<String, Object> object(
        String method,
        byte[] tl
    ) {
        return (Map<String, Object>) result(method, tl);
    }


    private Map<String, Object> readObject(TlReader reader) {
        int id = reader.readInt32();
        ConstructorSpec spec = byId.get(id);
        if (spec == null) {
            throw new IllegalStateException("unknown constructor id " + Integer.toHexString(id));
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("_", spec.predicate);
        Map<String, Integer> registers = new HashMap<String, Integer>();
        for (ParamSpec param : spec.params) {
            if (param.kind == ParamSpec.Kind.FLAGS) {
                registers.put(param.name, reader.readInt32());
                continue;
            }
            if (param.derivedFrom != null) {
                continue;
            }
            if (param.flagRegister != null && (registers.get(param.flagRegister) & (1 << param.flagBit)) == 0) {
                continue;
            }
            map.put(param.name, readValue(reader, param));
        }
        return map;
    }


    private Object readValue(
        TlReader reader,
        ParamSpec param
    ) {
        switch (param.kind) {
            case TRUE:
                return Boolean.TRUE;
            case BOOL:
                return readBool(reader);
            case INT:
                return Integer.valueOf(reader.readInt32());
            case LONG:
                return Long.valueOf(reader.readInt64());
            case DOUBLE:
                return Double.valueOf(reader.readDouble());
            case STRING:
                return reader.readString();
            case BYTES:
                return reader.readBytes();
            case INT128:
                return reader.readRaw(16);
            case INT256:
                return reader.readRaw(32);
            case VECTOR:
                return readVector(reader, param.elem);
            case OBJECT:
                return readObject(reader);
            default:
                throw new IllegalStateException("cannot read " + param.kind);
        }
    }


    private List<Object> readVector(
        TlReader reader,
        ParamSpec elem
    ) {
        int id = reader.readInt32();
        if (id != VectorId) {
            throw new IllegalStateException("expected vector id, got " + Integer.toHexString(id));
        }
        int count = reader.readInt32();
        List<Object> list = new ArrayList<Object>();
        for (int i = 0; i < count; i++) {
            list.add(readValue(reader, elem));
        }
        return list;
    }


    private static Boolean readBool(TlReader reader) {
        int id = reader.readInt32();
        if (id == BoolTrue) {
            return Boolean.TRUE;
        }
        if (id == BoolFalse) {
            return Boolean.FALSE;
        }
        throw new IllegalStateException("expected bool, got " + Integer.toHexString(id));
    }
}
