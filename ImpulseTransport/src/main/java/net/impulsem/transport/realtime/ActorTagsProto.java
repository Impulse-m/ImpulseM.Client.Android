package net.impulsem.transport.realtime;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.impulsem.transport.wire.ProtoReader;
import net.impulsem.transport.wire.ProtoWriter;


/** Protobuf bodies of the impulse.sync rpcs the realtime layer calls. */
public final class ActorTagsProto {

    /** The most channel ids one GetChannelActorTags call accepts. */
    public static final int MaxBatch = 200;


    private ActorTagsProto() {
    }


    /** GetChannelActorTagsRequest { repeated int64 channel_ids = 1; } */
    public static byte[] encodeRequest(List<Long> channelIds) {
        long[] values = new long[channelIds.size()];
        for (int a = 0; a < values.length; a++) {
            values[a] = channelIds.get(a);
        }
        ProtoWriter writer = new ProtoWriter();
        writer.writePackedVarints(1, values);
        return writer.toByteArray();
    }


    /** GetChannelActorTagsResponse { map<int64, string> tags = 1; } */
    public static Map<Long, String> decodeResponse(byte[] proto) {
        Map<Long, String> tags = new HashMap<Long, String>();
        ProtoReader reader = new ProtoReader(proto, 0, proto.length);
        while (reader.next()) {
            if (reader.field() != 1 || reader.wireType() != 2) {
                continue;
            }
            byte[] entry = reader.readBytes();
            ProtoReader entryReader = new ProtoReader(entry, 0, entry.length);
            long key = 0L;
            String value = null;
            while (entryReader.next()) {
                if (entryReader.field() == 1 && entryReader.wireType() == 0) {
                    key = entryReader.readVarint();
                } else if (entryReader.field() == 2 && entryReader.wireType() == 2) {
                    value = string(entryReader.readBytes());
                }
            }
            if (value != null) {
                tags.put(key, value);
            }
        }
        return tags;
    }


    /** GetCentrifugoTokenResponse { string token = 1; int64 expires_in = 2; }; null when no token is present. */
    public static String decodeToken(byte[] proto) {
        ProtoReader reader = new ProtoReader(proto, 0, proto.length);
        while (reader.next()) {
            if (reader.field() == 1 && reader.wireType() == 2) {
                return string(reader.readBytes());
            }
        }
        return null;
    }


    public static List<List<Long>> batches(
        List<Long> ids,
        int size
    ) {
        List<List<Long>> result = new ArrayList<List<Long>>();
        for (int a = 0; a < ids.size(); a += size) {
            result.add(new ArrayList<Long>(ids.subList(a, Math.min(ids.size(), a + size))));
        }
        return result;
    }


    private static String string(byte[] value) {
        try {
            return new String(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
