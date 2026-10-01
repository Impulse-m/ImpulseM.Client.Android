package net.impulsem.transport.realtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.google.gson.JsonParser;
import org.junit.Test;


public class PublicationRouterTest {

    private static Publication publication(
        String channel,
        String json
    ) {
        return new Publication(channel, Envelope.parse(JsonParser.parseString(json)), 1L);
    }


    @Test
    public void oversizeMeansDifference() {
        PublicationRouter.Action action = PublicationRouter.route(
            publication("user:5", "{\"type\":1,\"pts\":7,\"ptsCount\":1,\"date\":5,\"oversize\":true}")
        );
        assertEquals(PublicationRouter.Kind.GET_DIFFERENCE, action.kind);
    }


    @Test
    public void undecodableMeansDifference() {
        PublicationRouter.Action action = PublicationRouter.route(publication("channel:9", "[1,2]"));
        assertEquals(PublicationRouter.Kind.GET_DIFFERENCE, action.kind);
    }


    @Test
    public void bodyMeansDecode() {
        PublicationRouter.Action action = PublicationRouter.route(
            publication("user:5", "{\"type\":1,\"pts\":42,\"ptsCount\":2,\"date\":1700000000,\"data\":\"aGVsbG8=\"}")
        );
        assertEquals(PublicationRouter.Kind.DECODE, action.kind);
        assertArrayEquals("hello".getBytes(), action.proto);
    }


    @Test
    public void channelWithoutBodyIsTooLongSignal() {
        PublicationRouter.Action action = PublicationRouter.route(
            publication("channel:1234567890123", "{\"type\":215,\"pts\":99,\"ptsCount\":0,\"date\":77}")
        );
        assertEquals(PublicationRouter.Kind.CHANNEL_TOO_LONG, action.kind);
        assertEquals(1234567890123L, action.channelId);
        assertEquals(99, action.pts);
        assertEquals(77, action.date);
    }


    @Test
    public void userLaneWithoutBodyFallsBackToDifference() {
        PublicationRouter.Action action = PublicationRouter.route(
            publication("user:5", "{\"type\":215,\"pts\":99,\"ptsCount\":0,\"date\":77}")
        );
        assertEquals(PublicationRouter.Kind.GET_DIFFERENCE, action.kind);
    }


    @Test
    public void channelWithMalformedNameFallsBackToDifference() {
        PublicationRouter.Action action = PublicationRouter.route(
            publication("channel:abc", "{\"type\":215,\"pts\":99,\"ptsCount\":0,\"date\":77}")
        );
        assertEquals(PublicationRouter.Kind.GET_DIFFERENCE, action.kind);
    }


    @Test
    public void channelIdParsing() {
        assertEquals(42L, PublicationRouter.channelId("channel:42"));
        assertEquals(-1L, PublicationRouter.channelId("user:42"));
        assertEquals(-1L, PublicationRouter.channelId("channel:"));
        assertEquals(-1L, PublicationRouter.channelId(null));
    }
}
