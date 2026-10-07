package net.impulsem.proxy;

import java.util.ArrayList;
import java.util.List;


/** The whole proxy configuration. Owned and synchronised by the controller. */
public final class ProxyState {
    public boolean enabled;
    public boolean useForCalls;
    public String selectedId;
    public final List<ProxyServer> manual = new ArrayList<ProxyServer>();
    public final List<Subscription> subscriptions = new ArrayList<Subscription>();


    public ProxyServer selected() {
        if (selectedId == null) {
            return null;
        }
        for (ProxyServer server : allServers()) {
            if (server.id.equals(selectedId)) {
                return server;
            }
        }
        return null;
    }


    public List<ProxyServer> allServers() {
        List<ProxyServer> all = new ArrayList<ProxyServer>(manual);
        for (Subscription subscription : subscriptions) {
            all.addAll(subscription.servers);
        }
        return all;
    }


    public void addManual(List<ProxyServer> servers) {
        for (ProxyServer server : servers) {
            if (!containsId(manual, server.id)) {
                manual.add(server);
            }
        }
    }


    public void replaceSubscription(Subscription updated) {
        int index = indexOfSubscription(updated.id);
        boolean selectionWasHere = index >= 0 && containsId(subscriptions.get(index).servers, selectedId);
        if (index >= 0) {
            subscriptions.set(index, updated);
        } else {
            subscriptions.add(updated);
        }
        if (selectionWasHere && !containsId(updated.servers, selectedId)) {
            selectedId = updated.servers.isEmpty() ? null : updated.servers.get(0).id;
        }
    }


    public void removeServer(String id) {
        if (id == null) {
            return;
        }
        boolean removed = false;
        for (int i = manual.size() - 1; i >= 0; i--) {
            if (manual.get(i).id.equals(id)) {
                manual.remove(i);
                removed = true;
            }
        }
        if (removed && id.equals(selectedId)) {
            selectedId = null;
        }
    }


    public void removeSubscription(String id) {
        int index = indexOfSubscription(id);
        if (index < 0) {
            return;
        }
        if (containsId(subscriptions.get(index).servers, selectedId)) {
            selectedId = null;
        }
        subscriptions.remove(index);
    }


    private int indexOfSubscription(String id) {
        for (int i = 0; i < subscriptions.size(); i++) {
            if (subscriptions.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }


    private static boolean containsId(
        List<ProxyServer> servers,
        String id
    ) {
        if (id == null) {
            return false;
        }
        for (ProxyServer server : servers) {
            if (server.id.equals(id)) {
                return true;
            }
        }
        return false;
    }
}
