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


    /**
     * A publication arrived. When {@code publication.envelope.undecodable()} is true the payload could not be read
     * (garbage JSON, bad base64, non-numeric fields); the channel and offset are kept when parseable. The
     * application must treat it as a gap and run getDifference.
     */
    void onPublication(Publication publication);


    /**
     * The channel was unsubscribed by the server, or its subscribe was refused.
     * <ul>
     * <li>Codes of 2500 or more are temporary: the client resubscribes by itself and {@link #onSubscribed} follows.
     * After code 2502 that follow-up has {@code recovered = false} and {@code wasRecovering = false}, because the
     * position was dropped. The application must treat it as a gap and run getDifference.</li>
     * <li>Codes below 2500 are terminal: the channel is forgotten and not resubscribed.</li>
     * <li>A refused subscribe whose temporary-error retries are exhausted (or that failed with another code) is
     * terminal too; {@code code} is the Centrifugo error code.</li>
     * </ul>
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
