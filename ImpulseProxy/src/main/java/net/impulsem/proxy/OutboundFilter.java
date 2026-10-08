package net.impulsem.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;


/** Keeps the VLESS outbounds from a convertShareLinksToXrayJson result. */
public final class OutboundFilter {

    public static final class Result {
        public final List<ProxyServer> servers;
        public final int skipped;


        Result(
            List<ProxyServer> servers,
            int skipped
        ) {
            this.servers = servers;
            this.skipped = skipped;
        }
    }


    private OutboundFilter() {
    }


    public static Result filter(JsonArray outbounds) {
        List<ProxyServer> servers = new ArrayList<ProxyServer>();
        int skipped = 0;
        for (JsonElement element : outbounds) {
            ProxyServer server = toServer(element);
            if (server == null) {
                skipped++;
            } else {
                servers.add(server);
            }
        }
        return new Result(servers, skipped);
    }


    /** Returns null for anything that is not a usable VLESS outbound. */
    private static ProxyServer toServer(JsonElement element) {
        try {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject outbound = element.getAsJsonObject();
            JsonElement protocol = outbound.get("protocol");
            if (protocol == null || !"vless".equals(protocol.getAsString())) {
                return null;
            }
            return ProxyServer.fromOutbound(outbound);
        } catch (ClassCastException | IllegalStateException | NumberFormatException | UnsupportedOperationException e) {
            return null;
        }
    }
}
