package net.impulsem.transport.codec;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.impulsem.transport.schema.ConstructorSpec;
import net.impulsem.transport.schema.MethodSpec;
import net.impulsem.transport.schema.TlProtoSchema;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class TranscoderRoundTripTest {

    private static final int MaxReported = 100000;

    private final TlProtoSchema schema = TlProtoSchema.load();
    private final Transcoder transcoder = new Transcoder(schema);
    private final TlSynth synth = new TlSynth(schema);


    @Test
    public void everyConstructorRoundTrips() {
        List<String> failures = new ArrayList<String>();
        int checked = 0;
        for (ConstructorSpec spec : schema.constructors()) {
            for (boolean allFlags : new boolean[] {true, false}) {
                checked++;
                String label = spec.predicate + " (" + spec.type + ") allFlags=" + allFlags;
                try {
                    byte[] tl = synth.constructor(spec, allFlags);
                    byte[] proto = transcoder.tlObjectToProto(spec.type, tl);
                    byte[] back = transcoder.protoToTlObject(spec.type, proto, null);
                    if (!Arrays.equals(tl, back)) {
                        failures.add(label + ": TL differs after round trip, " + tl.length + " vs " + back.length + " bytes");
                    }
                } catch (RuntimeException e) {
                    failures.add(label + ": " + e);
                }
            }
        }
        System.out.println("constructor round trips checked: " + checked);
        assertTrue(checked >= 1653 * 2);
        report("constructors", failures, checked);
    }


    @Test
    public void everyMethodRequestRoundTrips() {
        List<String> failures = new ArrayList<String>();
        int checked = 0;
        for (MethodSpec spec : schema.methods()) {
            for (boolean allFlags : new boolean[] {true, false}) {
                checked++;
                String label = spec.method + " allFlags=" + allFlags;
                try {
                    byte[] tl = synth.method(spec, allFlags);
                    EncodedRequest request = transcoder.encodeRequest(tl);
                    if (request.methodId != spec.id || !request.path.equals(spec.path)) {
                        failures.add(label + ": wrong method " + request.methodId + " " + request.path);
                        continue;
                    }
                    byte[] back = transcoder.decodeRequestForTest(spec.id, request.proto);
                    if (!Arrays.equals(tl, back)) {
                        failures.add(label + ": TL differs after round trip");
                    }
                } catch (RuntimeException e) {
                    failures.add(label + ": " + e);
                }
            }
        }
        System.out.println("method round trips checked: " + checked);
        assertTrue(checked >= 802 * 2);
        report("methods", failures, checked);
    }


    @Test
    public void everyObjectResultDecodes() {
        List<String> failures = new ArrayList<String>();
        int checked = 0;
        for (MethodSpec spec : schema.methods()) {
            if (!spec.result.kind.equals("object")) {
                continue;
            }
            for (boolean allFlags : new boolean[] {true, false}) {
                checked++;
                String label = spec.method + " result " + spec.result.typeName + " allFlags=" + allFlags;
                try {
                    ConstructorSpec simplest = null;
                    for (ConstructorSpec candidate : schema.constructorsOf(spec.result.typeName)) {
                        if (simplest == null || candidate.params.size() < simplest.params.size()) {
                            simplest = candidate;
                        }
                    }
                    byte[] tl = synth.constructor(simplest, allFlags);
                    byte[] proto = transcoder.tlObjectToProto(spec.result.typeName, tl);
                    byte[] back = transcoder.decodeResult(spec.id, proto, null);
                    if (!Arrays.equals(tl, back)) {
                        failures.add(label + ": TL differs");
                    }
                } catch (RuntimeException e) {
                    failures.add(label + ": " + e);
                }
            }
        }
        System.out.println("object results checked: " + checked);
        assertTrue(checked > 0);
        report("object results", failures, checked);
    }


    private static void report(
        String what,
        List<String> failures,
        int checked
    ) {
        if (failures.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append(failures.size()).append(" of ").append(checked).append(" ").append(what).append(" failed:\n");
        for (int i = 0; i < failures.size() && i < MaxReported; i++) {
            message.append("  ").append(failures.get(i)).append('\n');
        }
        if (failures.size() > MaxReported) {
            message.append("  ... and ").append(failures.size() - MaxReported).append(" more\n");
        }
        fail(message.toString());
    }
}
