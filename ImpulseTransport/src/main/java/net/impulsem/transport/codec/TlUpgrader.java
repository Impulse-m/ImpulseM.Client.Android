package net.impulsem.transport.codec;

import net.impulsem.transport.schema.ConstructorSpec;
import net.impulsem.transport.schema.LegacySpec;
import net.impulsem.transport.schema.MethodSpec;
import net.impulsem.transport.schema.ParamSpec;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.TlReader;
import net.impulsem.transport.wire.TlWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Rewrites TL that uses constructor or method ids of older layers into the layer 229 layout.
 * A legacy object is parsed with its legacy layout into a value tree (nested objects may be current
 * or legacy), then written with the 229 layout, mapping params by name:
 * shared params are copied (with the int to double, int to long, Vector of int to Vector of
 * InputMessage and T to Vector of T adapters), params only in 229 are absent when conditional and zero otherwise, and
 * params only in the legacy layout are dropped. Any other kind change throws a TranscodeException.
 * Instances are stateless and thread-safe.
 */
public final class TlUpgrader {

    private static final int BoolTrue = 0x997275b5;
    private static final int BoolFalse = 0xbc799737;
    private static final int VectorId = 0x1cb5c415;
    private static final int MaxDepth = 256;
    private static final String InputMessageType = "InputMessage";
    private static final String InputMessageIdPredicate = "inputMessageID";

    private final TlProtoSchema schema;


    /** A parsed object in the layout of {@code params}. Values are keyed by param name; absent means not present. */
    private static final class Node {

        final int id;
        final List<ParamSpec> params;
        final Map<String, Object> values = new LinkedHashMap<String, Object>();


        Node(
            int id,
            List<ParamSpec> params
        ) {
            this.id = id;
            this.params = params;
        }
    }


    public TlUpgrader(TlProtoSchema schema) {
        this.schema = schema;
    }


    public boolean isLegacyMethod(int id) {
        return schema.method(id) == null && schema.legacyMethod(id) != null;
    }


    public boolean isLegacyConstructor(int id) {
        return schema.constructor(id) == null && schema.legacyConstructor(id) != null;
    }


    /** Upgrades one whole request (legacy method id first) to the layer 229 request. */
    public byte[] upgradeRequest(byte[] tl) {
        try {
            TlReader reader = new TlReader(tl, 0, tl.length);
            int id = reader.readInt32();
            byte[] result = upgradeMethodBody(id, reader);
            if (reader.remaining() != 0) {
                throw new TranscodeException("trailing bytes after legacy request " + Integer.toHexString(id));
            }
            return result;
        } catch (TranscodeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TranscodeException("cannot upgrade request: " + e.getMessage(), e);
        }
    }


    /**
     * Reads the params of a legacy method whose id was just consumed from the reader, and returns
     * the layer 229 call (target id included).
     */
    public byte[] upgradeMethodBody(
        int legacyId,
        TlReader reader
    ) {
        LegacySpec legacy = schema.legacyMethod(legacyId);
        if (legacy == null) {
            throw new TranscodeException("unknown method id " + Integer.toHexString(legacyId));
        }
        MethodSpec target = schema.method(legacy.targetId);
        if (target == null) {
            throw new TranscodeException("legacy method " + legacy.name + " targets a missing method");
        }
        Node node = readLegacy(reader, legacy, target.id, target.params, legacy.name, 0);
        TlWriter writer = new TlWriter();
        writeNode(writer, node, legacy.name);
        return writer.toByteArray();
    }


    /**
     * Reads one object of the given TL type (id first) that is current or legacy, with all nested
     * legacy objects, and returns it in layer 229 form.
     */
    public byte[] upgradeObject(
        TlReader reader,
        String type
    ) {
        try {
            Node node = readObject(reader, type, type, 0);
            TlWriter writer = new TlWriter();
            writeNode(writer, node, type);
            return writer.toByteArray();
        } catch (TranscodeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TranscodeException("cannot upgrade " + type + ": " + e.getMessage(), e);
        }
    }


    // ---------------------------------------------------------------- read


    /** Like {@link #upgradeObject}, for a reader whose object id was already consumed. */
    public byte[] upgradeObjectBody(
        int id,
        TlReader reader,
        String type
    ) {
        try {
            Node node = readObjectBody(id, reader, type, type, 0);
            TlWriter writer = new TlWriter();
            writeNode(writer, node, type);
            return writer.toByteArray();
        } catch (TranscodeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TranscodeException("cannot upgrade " + type + ": " + e.getMessage(), e);
        }
    }


    private Node readObject(
        TlReader reader,
        String type,
        String path,
        int depth
    ) {
        checkDepth(depth, path);
        return readObjectBody(reader.readInt32(), reader, type, path, depth);
    }


    private Node readObjectBody(
        int id,
        TlReader reader,
        String type,
        String path,
        int depth
    ) {
        ConstructorSpec current = schema.constructor(id);
        if (current != null) {
            if (!current.type.equals(type)) {
                throw new TranscodeException(path + ": constructor " + current.predicate + " is " + current.type + ", expected " + type);
            }
            Node node = new Node(current.id, current.params);
            readParams(reader, current.params, node.values, path + "." + current.predicate, depth);
            return node;
        }
        LegacySpec legacy = schema.legacyConstructor(id);
        if (legacy == null) {
            throw new TranscodeException("unknown constructor " + Integer.toHexString(id) + " while reading " + type);
        }
        ConstructorSpec target = schema.constructor(legacy.targetId);
        if (target == null || !target.type.equals(type)) {
            throw new TranscodeException(path + ": legacy constructor " + legacy.name + " is not a " + type);
        }
        return readLegacy(reader, legacy, target.id, target.params, path + "." + legacy.name, depth);
    }


    private Node readLegacy(
        TlReader reader,
        LegacySpec legacy,
        int targetId,
        List<ParamSpec> targetParams,
        String path,
        int depth
    ) {
        checkDepth(depth, path);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        readParams(reader, legacy.params, values, path, depth);
        Node node = new Node(targetId, targetParams);
        Map<String, ParamSpec> legacyByName = new HashMap<String, ParamSpec>();
        for (ParamSpec param : legacy.params) {
            if (param.kind != ParamSpec.Kind.FLAGS) {
                legacyByName.put(param.name, param);
            }
        }
        for (ParamSpec target : targetParams) {
            if (target.kind == ParamSpec.Kind.FLAGS) {
                continue;
            }
            ParamSpec old = legacyByName.get(target.name);
            String paramPath = path + "." + target.name;
            if (old == null) {
                if (target.flagRegister == null) {
                    node.values.put(target.name, defaultValue(target, paramPath));
                }
                continue;
            }
            Object value = values.get(target.name);
            if (value == null) {
                // Present in the legacy layout but flagged off there.
                if (target.flagRegister == null) {
                    node.values.put(target.name, defaultValue(target, paramPath));
                }
                continue;
            }
            node.values.put(target.name, adapt(value, old, target, paramPath));
        }
        fillSharedFlagBits(node, path);
        return node;
    }


    /**
     * Several 229 params can share one flag bit (all present or all absent). When the legacy layout
     * supplied only some of them, the others get their zero value.
     */
    private void fillSharedFlagBits(
        Node node,
        String path
    ) {
        for (ParamSpec param : node.params) {
            if (param.flagRegister == null || param.kind == ParamSpec.Kind.FLAGS || param.kind == ParamSpec.Kind.TRUE) {
                continue;
            }
            if (node.values.containsKey(param.name)) {
                continue;
            }
            for (ParamSpec other : node.params) {
                if (other != param
                    && param.flagRegister.equals(other.flagRegister)
                    && param.flagBit == other.flagBit
                    && node.values.containsKey(other.name)
                    && !(other.kind == ParamSpec.Kind.TRUE && !Boolean.TRUE.equals(node.values.get(other.name)))) {
                    node.values.put(param.name, defaultValue(param, path + "." + param.name));
                    break;
                }
            }
        }
    }


    private void readParams(
        TlReader reader,
        List<ParamSpec> params,
        Map<String, Object> values,
        String path,
        int depth
    ) {
        Map<String, Integer> registers = new HashMap<String, Integer>();
        for (ParamSpec param : params) {
            if (param.kind == ParamSpec.Kind.FLAGS) {
                registers.put(param.name, Integer.valueOf(reader.readInt32()));
                continue;
            }
            if (param.flagRegister != null) {
                Integer register = registers.get(param.flagRegister);
                if (register == null || (register.intValue() & (1 << param.flagBit)) == 0) {
                    continue;
                }
            }
            values.put(param.name, readValue(reader, param, path + "." + param.name, depth));
        }
    }


    private Object readValue(
        TlReader reader,
        ParamSpec param,
        String path,
        int depth
    ) {
        switch (param.kind) {
            case TRUE:
                return Boolean.TRUE;
            case BOOL: {
                int id = reader.readInt32();
                if (id == BoolTrue) {
                    return Boolean.TRUE;
                } else if (id == BoolFalse) {
                    return Boolean.FALSE;
                }
                throw new TranscodeException(path + ": bad Bool " + Integer.toHexString(id));
            }
            case INT:
                return Integer.valueOf(reader.readInt32());
            case LONG:
                return Long.valueOf(reader.readInt64());
            case DOUBLE:
                return Double.valueOf(reader.readDouble());
            case STRING:
            case BYTES:
                return reader.readBytes();
            case INT128:
                return reader.readRaw(16);
            case INT256:
                return reader.readRaw(32);
            case OBJECT:
                return readObject(reader, param.typeName, path, depth + 1);
            case VECTOR: {
                int vector = reader.readInt32();
                if (vector != VectorId) {
                    throw new TranscodeException(path + ": expected vector, got " + Integer.toHexString(vector));
                }
                int count = reader.readInt32();
                if (count < 0 || count > reader.remaining()) {
                    throw new TranscodeException(path + ": bad vector length " + count);
                }
                List<Object> list = new ArrayList<Object>(count);
                for (int i = 0; i < count; i++) {
                    list.add(readValue(reader, param.elem, path + "[" + i + "]", depth));
                }
                return list;
            }
            default:
                throw new TranscodeException(path + ": cannot read kind " + param.kind);
        }
    }


    // ---------------------------------------------------------------- convert


    private Object adapt(
        Object value,
        ParamSpec from,
        ParamSpec to,
        String path
    ) {
        if (from.kind == ParamSpec.Kind.VECTOR && to.kind == ParamSpec.Kind.VECTOR) {
            if (from.elem.kind == ParamSpec.Kind.INT
                && to.elem.kind == ParamSpec.Kind.OBJECT
                && InputMessageType.equals(to.elem.typeName)) {
                return wrapMessageIds((List<?>) value);
            }
            List<?> source = (List<?>) value;
            List<Object> result = new ArrayList<Object>(source.size());
            for (int i = 0; i < source.size(); i++) {
                result.add(adapt(source.get(i), from.elem, to.elem, path + "[" + i + "]"));
            }
            return result;
        }
        if (from.kind == ParamSpec.Kind.OBJECT
            && to.kind == ParamSpec.Kind.VECTOR
            && to.elem.kind == ParamSpec.Kind.OBJECT
            && from.typeName.equals(to.elem.typeName)) {
            List<Object> single = new ArrayList<Object>(1);
            single.add(value);
            return single;
        }
        if (from.kind == to.kind) {
            if (from.kind == ParamSpec.Kind.OBJECT && !from.typeName.equals(to.typeName)) {
                throw new TranscodeException(path + ": cannot upgrade object type " + from.typeName + " to " + to.typeName);
            }
            return value;
        }
        if (from.kind == ParamSpec.Kind.INT && to.kind == ParamSpec.Kind.DOUBLE) {
            return Double.valueOf(((Integer) value).doubleValue());
        }
        if (from.kind == ParamSpec.Kind.INT && to.kind == ParamSpec.Kind.LONG) {
            return Long.valueOf(((Integer) value).longValue());
        }
        throw new TranscodeException(path + ": cannot upgrade kind " + kindName(from) + " to " + kindName(to));
    }


    private static String kindName(ParamSpec spec) {
        if (spec.kind == ParamSpec.Kind.VECTOR) {
            return "Vector<" + kindName(spec.elem) + ">";
        }
        if (spec.kind == ParamSpec.Kind.OBJECT) {
            return spec.typeName;
        }
        return spec.kind.name().toLowerCase(java.util.Locale.ROOT);
    }


    private List<Object> wrapMessageIds(List<?> ids) {
        ConstructorSpec wrapper = null;
        for (ConstructorSpec candidate : schema.constructorsOf(InputMessageType)) {
            if (candidate.predicate.equals(InputMessageIdPredicate)) {
                wrapper = candidate;
            }
        }
        if (wrapper == null) {
            throw new TranscodeException("constructor " + InputMessageIdPredicate + " is missing");
        }
        List<Object> result = new ArrayList<Object>(ids.size());
        for (Object id : ids) {
            Node node = new Node(wrapper.id, wrapper.params);
            node.values.put("id", id);
            result.add(node);
        }
        return result;
    }


    private Object defaultValue(
        ParamSpec param,
        String path
    ) {
        switch (param.kind) {
            case BOOL:
                return Boolean.FALSE;
            case INT:
                return Integer.valueOf(0);
            case LONG:
                return Long.valueOf(0L);
            case DOUBLE:
                return Double.valueOf(0.0);
            case STRING:
            case BYTES:
                return new byte[0];
            case INT128:
                return new byte[16];
            case INT256:
                return new byte[32];
            case VECTOR:
                return new ArrayList<Object>();
            case OBJECT: {
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
                    throw new TranscodeException(path + ": no value in the legacy layout and no zero-param constructor of " + param.typeName);
                }
                return new Node(fallback.id, fallback.params);
            }
            default:
                throw new TranscodeException(path + ": no default for kind " + param.kind);
        }
    }


    // ---------------------------------------------------------------- write


    @SuppressWarnings("unchecked")
    private void writeNode(
        TlWriter writer,
        Node node,
        String path
    ) {
        writer.writeInt32(node.id);
        Map<String, Integer> registers = new LinkedHashMap<String, Integer>();
        for (ParamSpec param : node.params) {
            if (param.kind == ParamSpec.Kind.FLAGS) {
                registers.put(param.name, Integer.valueOf(0));
            }
        }
        for (ParamSpec param : node.params) {
            if (param.flagRegister == null || param.kind == ParamSpec.Kind.FLAGS) {
                continue;
            }
            Object value = node.values.get(param.name);
            if (value == null) {
                continue;
            }
            if (param.kind == ParamSpec.Kind.TRUE && !Boolean.TRUE.equals(value)) {
                continue;
            }
            Integer register = registers.get(param.flagRegister);
            if (register == null) {
                throw new TranscodeException(path + ": flag register " + param.flagRegister + " is not declared");
            }
            registers.put(param.flagRegister, Integer.valueOf(register.intValue() | (1 << param.flagBit)));
        }
        for (ParamSpec param : node.params) {
            if (param.kind == ParamSpec.Kind.FLAGS) {
                writer.writeInt32(registers.get(param.name).intValue());
                continue;
            }
            if (param.flagRegister != null && (registers.get(param.flagRegister).intValue() & (1 << param.flagBit)) == 0) {
                continue;
            }
            Object value = node.values.get(param.name);
            if (value == null && param.kind != ParamSpec.Kind.TRUE) {
                throw new TranscodeException(path + "." + param.name + ": no value");
            }
            writeValue(writer, param, value, path + "." + param.name);
        }
    }


    @SuppressWarnings("unchecked")
    private void writeValue(
        TlWriter writer,
        ParamSpec param,
        Object value,
        String path
    ) {
        switch (param.kind) {
            case TRUE:
                break;
            case BOOL:
                writer.writeInt32(((Boolean) value).booleanValue() ? BoolTrue : BoolFalse);
                break;
            case INT:
                writer.writeInt32(((Integer) value).intValue());
                break;
            case LONG:
                writer.writeInt64(((Long) value).longValue());
                break;
            case DOUBLE:
                writer.writeDouble(((Double) value).doubleValue());
                break;
            case STRING:
            case BYTES:
                writer.writeBytes((byte[]) value);
                break;
            case INT128:
            case INT256:
                writer.writeRaw((byte[]) value);
                break;
            case OBJECT:
                writeNode(writer, (Node) value, path);
                break;
            case VECTOR: {
                List<Object> list = (List<Object>) value;
                writer.writeInt32(VectorId);
                writer.writeInt32(list.size());
                for (int i = 0; i < list.size(); i++) {
                    writeValue(writer, param.elem, list.get(i), path + "[" + i + "]");
                }
                break;
            }
            default:
                throw new TranscodeException(path + ": cannot write kind " + param.kind);
        }
    }


    private static void checkDepth(
        int depth,
        String path
    ) {
        if (depth > MaxDepth) {
            throw new TranscodeException("nesting too deep at " + path);
        }
    }
}
