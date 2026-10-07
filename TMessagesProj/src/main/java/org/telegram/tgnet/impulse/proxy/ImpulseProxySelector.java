package org.telegram.tgnet.impulse.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import net.impulsem.proxy.ProxyRouting;
import okhttp3.Credentials;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;


/** OkHttp routing for ImpulseM traffic: DIRECT when the proxy is off, the local core when running, a dead endpoint otherwise. */
public final class ImpulseProxySelector extends ProxySelector {
    public static final ImpulseProxySelector Instance = new ImpulseProxySelector();

    public static final okhttp3.Authenticator Authenticator = new okhttp3.Authenticator() {
        @Override
        public Request authenticate(
            Route route,
            Response response
        ) {
            ProxyController controller = ProxyController.getInstance();
            String user = controller.user();
            String password = controller.password();
            if (user == null || password == null || response.request().header("Proxy-Authorization") != null) {
                return null;
            }
            return response.request().newBuilder()
                .header("Proxy-Authorization", Credentials.basic(user, password))
                .build();
        }
    };

    // The discard port on loopback: connections fail immediately. An empty list would make OkHttp go DIRECT.
    private static final Proxy Blackhole = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getLoopbackAddress(), 9));


    private ImpulseProxySelector() {
    }


    @Override
    public List<Proxy> select(URI uri) {
        ProxyController controller = ProxyController.getInstance();
        ProxyRouting.Route route = controller.route();
        if (route == ProxyRouting.Route.DIRECT) {
            return Collections.singletonList(Proxy.NO_PROXY);
        }
        InetSocketAddress endpoint = controller.httpEndpoint();
        if (route == ProxyRouting.Route.PROXY && endpoint != null) {
            return Collections.singletonList(new Proxy(Proxy.Type.HTTP, endpoint));
        }
        return Collections.singletonList(Blackhole);
    }


    @Override
    public void connectFailed(
        URI uri,
        SocketAddress address,
        IOException failure
    ) {
        // Reconnection is driven by the transport's retry policy.
    }
}
