package net.impulsem.transport.realtime;


/** Realtime lifecycle and data events. Callbacks run on the socket reader or the executor thread; keep them short. */
public interface CentrifugoListener {

    void onConnected();


    /**
     * A channel subscription is established.
     *
     * @param recovered true when the server replayed the missed publications.
     * @param wasRecovering true when this subscribe asked for recovery.
     */
    void onSubscribed(
        String channel,
        boolean recovered,
        boolean wasRecovering
    );


    void onPublication(Publication publication);


    /**
     * The channel was unsubscribed by the server or refused a subscribe. For server pushes with code
     * 2500 or more the client resubscribes by itself right after this call.
     */
    void onUnsubscribed(
        String channel,
        int code,
        String reason
    );


    void onDisconnected(
        int code,
        String reason,
        boolean willReconnect
    );
}
