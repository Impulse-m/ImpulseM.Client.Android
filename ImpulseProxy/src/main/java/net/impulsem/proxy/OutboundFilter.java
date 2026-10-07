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
            if (!element.isJsonObject()) {
                skipped++;
                continue;
            }
            JsonObject outbound = element.getAsJsonObject();
            String protocol = outbound.has("protocol") ? outbound.get("protocol").getAsString() : "";
            ProxyServer server = "vless".equals(protocol) ? ProxyServer.fromOutbound(outbound) : null;
            if (server == null) {
                skipped++;
            } else {
                servers.add(server);
            }
        }
        return new Result(servers, skipped);
    }
}
