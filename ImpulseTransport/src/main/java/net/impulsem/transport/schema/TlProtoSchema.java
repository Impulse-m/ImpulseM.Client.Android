package net.impulsem.transport.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;


public final class TlProtoSchema {

    private static final String Resource = "/impulse/tl-proto-229.json.gz";

    private static volatile TlProtoSchema instance;

    public final int layer;

    private final Map<Integer, ConstructorSpec> constructors = new HashMap<Integer, ConstructorSpec>();
    private final Map<String, TypeSpec> types = new HashMap<String, TypeSpec>();
    private final Map<Integer, MethodSpec> methods = new HashMap<Integer, MethodSpec>();


    private TlProtoSchema(JsonObject root) {
        layer = root.get("layer").getAsInt();
        for (JsonElement element : root.getAsJsonArray("constructors")) {
            ConstructorSpec spec = parseConstructor(element.getAsJsonObject());
            constructors.put(spec.id, spec);
        }
        for (JsonElement element : root.getAsJsonArray("types")) {
            TypeSpec spec = parseType(element.getAsJsonObject());
            types.put(spec.name, spec);
        }
        for (JsonElement element : root.getAsJsonArray("methods")) {
            MethodSpec spec = parseMethod(element.getAsJsonObject());
            methods.put(spec.id, spec);
        }
    }


    public static TlProtoSchema load() {
        TlProtoSchema local = instance;
        if (local == null) {
            synchronized (TlProtoSchema.class) {
                local = instance;
                if (local == null) {
                    local = read();
                    instance = local;
                }
            }
        }
        return local;
    }


    public ConstructorSpec constructor(int id) {
        return constructors.get(id);
    }


    public TypeSpec type(String tlTypeName) {
        return types.get(tlTypeName);
    }


    public MethodSpec method(int id) {
        return methods.get(id);
    }


    public ConstructorSpec constructorByArm(TypeSpec type, int armField) {
        Integer id = type.arms.get(armField);
        if (id == null) {
            return null;
        }
        return constructors.get(id);
    }


    private static TlProtoSchema read() {
        InputStream raw = TlProtoSchema.class.getResourceAsStream(Resource);
        if (raw == null) {
            throw new IllegalStateException("missing resource " + Resource);
        }
        try {
            Reader reader = new InputStreamReader(new GZIPInputStream(raw), "UTF-8");
            try {
                return new TlProtoSchema(JsonParser.parseReader(reader).getAsJsonObject());
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + Resource, e);
        }
    }


    private static ConstructorSpec parseConstructor(JsonObject json) {
        ConstructorSpec spec = new ConstructorSpec();
        spec.id = json.get("id").getAsInt();
        spec.predicate = string(json, "predicate");
        spec.type = string(json, "type");
        spec.proto = string(json, "proto");
        if (json.has("arm") && !json.get("arm").isJsonNull()) {
            spec.arm = json.get("arm").getAsInt();
        }
        for (JsonElement element : json.getAsJsonArray("params")) {
            spec.params.add(parseParam(element.getAsJsonObject()));
        }
        if (json.has("capture")) {
            for (JsonElement element : json.getAsJsonArray("capture")) {
                JsonObject captureJson = element.getAsJsonObject();
                ConstructorSpec.Capture capture = new ConstructorSpec.Capture();
                capture.field = captureJson.get("field").getAsInt();
                capture.name = captureJson.get("name").getAsString();
                spec.capture.add(capture);
            }
        }
        return spec;
    }


    private static TypeSpec parseType(JsonObject json) {
        TypeSpec spec = new TypeSpec();
        spec.name = json.get("name").getAsString();
        spec.proto = string(json, "proto");
        spec.polymorphic = json.has("polymorphic") && json.get("polymorphic").getAsBoolean();
        if (json.has("arms")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("arms").entrySet()) {
                spec.arms.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsInt());
            }
        }
        return spec;
    }


    private static MethodSpec parseMethod(JsonObject json) {
        MethodSpec spec = new MethodSpec();
        spec.id = json.get("id").getAsInt();
        spec.method = json.get("method").getAsString();
        spec.path = json.get("path").getAsString();
        for (JsonElement element : json.getAsJsonArray("params")) {
            spec.params.add(parseParam(element.getAsJsonObject()));
        }
        JsonObject resultJson = json.getAsJsonObject("result");
        ResultSpec result = new ResultSpec();
        result.kind = resultJson.get("kind").getAsString();
        result.typeName = string(resultJson, "type");
        if (resultJson.has("elem")) {
            result.elem = parseParam(resultJson.getAsJsonObject("elem"));
        }
        spec.result = result;
        return spec;
    }


    private static ParamSpec parseParam(JsonObject json) {
        ParamSpec spec = new ParamSpec();
        if (json.has("name")) {
            spec.name = json.get("name").getAsString();
        }
        spec.kind = parseKind(json.get("kind").getAsString());
        spec.typeName = string(json, "type");
        if (json.has("elem")) {
            spec.elem = parseParam(json.getAsJsonObject("elem"));
        }
        if (json.has("flag")) {
            JsonArray flag = json.getAsJsonArray("flag");
            spec.flagRegister = flag.get(0).getAsString();
            spec.flagBit = flag.get(1).getAsInt();
        }
        if (json.has("field")) {
            spec.field = json.get("field").getAsInt();
        }
        spec.derivedFrom = string(json, "derived_from");
        return spec;
    }


    private static ParamSpec.Kind parseKind(String kind) {
        String normalized = kind.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("int")) {
            return ParamSpec.Kind.INT;
        } else if (normalized.equals("long")) {
            return ParamSpec.Kind.LONG;
        } else if (normalized.equals("double")) {
            return ParamSpec.Kind.DOUBLE;
        } else if (normalized.equals("string")) {
            return ParamSpec.Kind.STRING;
        } else if (normalized.equals("bytes")) {
            return ParamSpec.Kind.BYTES;
        } else if (normalized.equals("int128")) {
            return ParamSpec.Kind.INT128;
        } else if (normalized.equals("int256")) {
            return ParamSpec.Kind.INT256;
        } else if (normalized.equals("bool")) {
            return ParamSpec.Kind.BOOL;
        } else if (normalized.equals("true")) {
            return ParamSpec.Kind.TRUE;
        } else if (normalized.equals("flags")) {
            return ParamSpec.Kind.FLAGS;
        } else if (normalized.equals("vector")) {
            return ParamSpec.Kind.VECTOR;
        } else if (normalized.equals("object")) {
            return ParamSpec.Kind.OBJECT;
        }
        throw new IllegalStateException("unknown param kind " + kind);
    }


    private static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }
}
