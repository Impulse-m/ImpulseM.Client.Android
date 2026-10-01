package net.impulsem.transport.codec;

import net.impulsem.transport.schema.ConstructorSpec;
import net.impulsem.transport.schema.LegacySpec;
import net.impulsem.transport.schema.MethodSpec;
import net.impulsem.transport.schema.ParamSpec;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.TlWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/** Test helper: deterministic TL bytes for any constructor or method. */
final class TlSynth {

    static final int BoolTrue = 0x997275b5;
    static final int BoolFalse = 0xbc799737;
    static final int VectorId = 0x1cb5c415;

    private static final int GuardDepth = 4;
    private static final int HardDepth = 16;

    private final TlProtoSchema schema;
    private final Map<String, ConstructorSpec> simplest = new HashMap<String, ConstructorSpec>();


    TlSynth(TlProtoSchema schema) {
        this.schema = schema;
    }


    byte[] constructor(ConstructorSpec spec, boolean allFlags) {
        TlWriter writer = new TlWriter();
        writeConstructor(writer, spec, allFlags, 0);
        return writer.toByteArray();
    }


    byte[] method(MethodSpec spec, boolean allFlags) {
        TlWriter writer = new TlWriter();
        writer.writeInt32(spec.id);
        writeParams(writer, spec.params, allFlags, 0);
        return writer.toByteArray();
    }


    /** A legacy-layout constructor or method call (its own id, its own params). */
    byte[] legacy(
        LegacySpec spec,
        boolean allFlags
    ) {
        TlWriter writer = new TlWriter();
        writer.writeInt32(spec.id);
        writeParams(writer, spec.params, allFlags, 0);
        return writer.toByteArray();
    }


    private void writeConstructor(
        TlWriter writer,
        ConstructorSpec spec,
        boolean allFlags,
        int depth
    ) {
        writer.writeInt32(spec.id);
        writeParams(writer, spec.params, allFlags, depth);
    }


    private void writeParams(
        TlWriter writer,
        List<ParamSpec> params,
        boolean allFlags,
        int depth
    ) {
        Map<String, Integer> registers = new HashMap<String, Integer>();
        for (ParamSpec param : params) {
            if (param.kind == ParamSpec.Kind.FLAGS) {
                registers.put(param.name, 0);
            }
        }
        if (allFlags) {
            for (ParamSpec param : params) {
                if (param.flagRegister != null) {
                    registers.put(param.flagRegister, registers.get(param.flagRegister) | (1 << param.flagBit));
                }
            }
        }
        for (ParamSpec param : params) {
            if (param.kind == ParamSpec.Kind.FLAGS) {
                writer.writeInt32(registers.get(param.name));
                continue;
            }
            if (param.derivedFrom != null) {
                continue;
            }
            if (param.flagRegister != null && !allFlags) {
                continue;
            }
            writeValue(writer, param, param.name, allFlags, depth);
        }
    }


    private void writeValue(
        TlWriter writer,
        ParamSpec param,
        String name,
        boolean allFlags,
        int depth
    ) {
        switch (param.kind) {
            case TRUE:
                break;
            case BOOL:
                writer.writeInt32(BoolTrue);
                break;
            case INT:
                writer.writeInt32(7);
                break;
            case LONG:
                writer.writeInt64(1234567890123L);
                break;
            case DOUBLE:
                writer.writeDouble(1.5);
                break;
            case STRING:
                writer.writeString("s_" + name);
                break;
            case BYTES:
                writer.writeBytes(new byte[] {1, 2, 3});
                break;
            case INT128:
                writer.writeRaw(filled(16, (byte) 0x11));
                break;
            case INT256:
                writer.writeRaw(filled(32, (byte) 0x22));
                break;
            case VECTOR:
                writer.writeInt32(VectorId);
                if (depth >= GuardDepth) {
                    // Recursive types such as SecureRequiredType would never terminate otherwise.
                    writer.writeInt32(0);
                    break;
                }
                writer.writeInt32(2);
                writeValue(writer, param.elem, name, allFlags, depth);
                writeValue(writer, param.elem, name + "2", allFlags, depth);
                break;
            case OBJECT:
                writeObject(writer, param.typeName, allFlags, depth);
                break;
            default:
                throw new IllegalStateException("cannot synthesize " + param.kind);
        }
    }


    private void writeObject(
        TlWriter writer,
        String type,
        boolean allFlags,
        int depth
    ) {
        if (depth > HardDepth) {
            throw new IllegalStateException("synthesis recursion for " + type);
        }
        ConstructorSpec spec = simplestOf(type);
        if (depth >= GuardDepth) {
            writeConstructor(writer, spec, false, depth + 1);
        } else {
            writeConstructor(writer, spec, allFlags, depth + 1);
        }
    }


    private ConstructorSpec simplestOf(String type) {
        ConstructorSpec cached = simplest.get(type);
        if (cached != null) {
            return cached;
        }
        List<ConstructorSpec> list = new ArrayList<ConstructorSpec>(schema.constructorsOf(type));
        if (list.isEmpty()) {
            throw new IllegalStateException("no constructors for type " + type);
        }
        Collections.sort(list, new Comparator<ConstructorSpec>() {
            @Override
            public int compare(ConstructorSpec a, ConstructorSpec b) {
                if (a.params.size() != b.params.size()) {
                    return Integer.compare(a.params.size(), b.params.size());
                }
                return Integer.compare(a.id, b.id);
            }
        });
        simplest.put(type, list.get(0));
        return list.get(0);
    }


    private static byte[] filled(int length, byte value) {
        byte[] data = new byte[length];
        Arrays.fill(data, value);
        return data;
    }
}
