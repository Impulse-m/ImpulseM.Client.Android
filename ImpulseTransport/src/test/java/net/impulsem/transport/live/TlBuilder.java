package net.impulsem.transport.live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.impulsem.transport.schema.ConstructorSpec;
import net.impulsem.transport.schema.MethodSpec;
import net.impulsem.transport.schema.ParamSpec;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.TlWriter;


/**
 * Schema-driven TL writer by name. Flag registers are computed from the params that were put;
 * every non-flagged param must be present. Values: Number for int/long, String, byte[] for bytes
 * and int128/int256, Boolean for bool and true, List for vectors and TlBuilder for objects.
 */
public final class TlBuilder {

    private static final int BoolTrue = 0x997275b5;
    private static final int BoolFalse = 0xbc799737;
    private static final int VectorId = 0x1cb5c415;

    private final String name;
    private final int id;
    private final List<ParamSpec> params;
    private final Map<String, Object> values = new LinkedHashMap<String, Object>();


    private TlBuilder(
        String name,
        int id,
        List<ParamSpec> params
    ) {
        this.name = name;
        this.id = id;
        this.params = params;
    }


    public static TlBuilder method(String method) {
        TlProtoSchema schema = TlProtoSchema.load();
        for (MethodSpec spec : schema.methods()) {
            if (spec.method.equals(method)) {
                return new TlBuilder(method, spec.id, spec.params);
            }
        }
        throw new IllegalArgumentException("unknown method " + method);
    }


    public static TlBuilder object(String predicate) {
        TlProtoSchema schema = TlProtoSchema.load();
        for (ConstructorSpec spec : schema.constructors()) {
            if (predicate.equals(spec.predicate)) {
                return new TlBuilder(predicate, spec.id, spec.params);
            }
        }
        throw new IllegalArgumentException("unknown constructor " + predicate);
    }


    public static List<Object> list(Object... items) {
        List<Object> result = new ArrayList<Object>();
        for (Object item : items) {
            result.add(item);
        }
        return result;
    }


    public TlBuilder put(
        String param,
        Object value
    ) {
        for (ParamSpec spec : params) {
            if (spec.name.equals(param) && spec.kind != ParamSpec.Kind.FLAGS) {
                values.put(param, value);
                return this;
            }
        }
        throw new IllegalArgumentException(name + " has no param " + param);
    }


    public byte[] toBytes() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(id);
        Map<String, Integer> registers = new LinkedHashMap<String, Integer>();
        for (ParamSpec spec : params) {
            if (spec.kind == ParamSpec.Kind.FLAGS) {
                registers.put(spec.name, 0);
            }
        }
        for (ParamSpec spec : params) {
            if (spec.flagRegister != null && values.containsKey(spec.name)) {
                Object value = values.get(spec.name);
                if (spec.kind == ParamSpec.Kind.TRUE && !Boolean.TRUE.equals(value)) {
                    continue;
                }
                registers.put(spec.flagRegister, registers.get(spec.flagRegister) | (1 << spec.flagBit));
            }
        }
        for (ParamSpec spec : params) {
            if (spec.kind == ParamSpec.Kind.FLAGS) {
                writer.writeInt32(registers.get(spec.name));
                continue;
            }
            if (spec.derivedFrom != null) {
                continue;
            }
            if (spec.flagRegister != null && (registers.get(spec.flagRegister) & (1 << spec.flagBit)) == 0) {
                continue;
            }
            if (!values.containsKey(spec.name)) {
                throw new IllegalStateException(name + ": param " + spec.name + " is not set");
            }
            writeValue(writer, spec, values.get(spec.name));
        }
        return writer.toByteArray();
    }


    private void writeValue(
        TlWriter writer,
        ParamSpec spec,
        Object value
    ) {
        switch (spec.kind) {
            case TRUE:
                break;
            case BOOL:
                writer.writeInt32(((Boolean) value).booleanValue() ? BoolTrue : BoolFalse);
                break;
            case INT:
                writer.writeInt32(((Number) value).intValue());
                break;
            case LONG:
                writer.writeInt64(((Number) value).longValue());
                break;
            case DOUBLE:
                writer.writeDouble(((Number) value).doubleValue());
                break;
            case STRING:
                writer.writeString((String) value);
                break;
            case BYTES:
                writer.writeBytes((byte[]) value);
                break;
            case INT128:
            case INT256:
                writer.writeRaw((byte[]) value);
                break;
            case VECTOR:
                List<?> list = (List<?>) value;
                writer.writeInt32(VectorId);
                writer.writeInt32(list.size());
                for (Object element : list) {
                    writeValue(writer, spec.elem, element);
                }
                break;
            case OBJECT:
                writer.writeRaw(((TlBuilder) value).toBytes());
                break;
            default:
                throw new IllegalStateException("cannot write " + spec.kind);
        }
    }
}
