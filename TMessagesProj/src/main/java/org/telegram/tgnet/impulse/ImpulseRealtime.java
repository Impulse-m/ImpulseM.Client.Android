package org.telegram.tgnet.impulse;

import net.impulsem.transport.auth.ImpulseRpcPaths;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.logging.LogLevel;
import net.impulsem.transport.realtime.ActorTagsProto;
import net.impulsem.transport.realtime.CentrifugoClient;
import net.impulsem.transport.realtime.CentrifugoListener;
import net.impulsem.transport.realtime.GapTracker;
import net.impulsem.transport.realtime.Publication;
import net.impulsem.transport.realtime.PublicationRouter;
import net.impulsem.transport.realtime.SyncPlanner;
import net.impulsem.transport.rpc.RpcClient;
import net.impulsem.transport.rpc.SessionLostException;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.KeepAliveJob;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.remotelog.RemoteLog;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLClassStore;
import org.telegram.tgnet.TLDataSourceType;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;


/**
 * The realtime side of one account: a Centrifugo connection that carries the user lane and the channel lanes.
 * Every (re)subscribe of the user lane stands in for the old onSessionCreated and runs getDifference; publications
 * become TLRPC Updates and go to MessagesController.processUpdates.
 */
public final class ImpulseRealtime implements CentrifugoListener, CentrifugoClient.TokenProvider {

    private static final long EnsureMillis = 5L * 1000L;

    private static final ImpulseRealtime[] Instances = new ImpulseRealtime[UserConfig.MAX_ACCOUNT_COUNT];


    private final int account;
    private final ImpulseConnection connection;
    private final ScheduledExecutorService executor;
    private final CentrifugoClient client;
    private final GapTracker gaps = new GapTracker();
    private final ChannelLaneManager lanes;
    private final Object lock = new Object();

    private final SyncPlanner planner = new SyncPlanner();
    /** Runs every sync one at a time; kept apart from the executor of the Centrifugo client. */
    private final ScheduledThreadPoolExecutor syncExecutor;
    private boolean started;


    /** Creates the realtime component of an account and makes it reachable for the ChatActivity hooks. */
    static synchronized ImpulseRealtime create(
        int account,
        ImpulseConnection connection
    ) {
        ImpulseRealtime created = new ImpulseRealtime(account, connection);
        Instances[account] = created;
        return created;
    }


    private static synchronized ImpulseRealtime instance(int account) {
        if (account < 0 || account >= Instances.length) {
            return null;
        }
        return Instances[account];
    }


    /** Called from ChatActivity.onResume. */
    public static void onChatOpened(
        int account,
        long dialogId
    ) {
        ImpulseRealtime realtime = instance(account);
        if (realtime != null) {
            realtime.lanes.onChatOpened(dialogId);
        }
    }


    /** Called from ChatActivity.onPause. */
    public static void onChatClosed(
        int account,
        long dialogId
    ) {
        ImpulseRealtime realtime = instance(account);
        if (realtime != null) {
            realtime.lanes.onChatClosed(dialogId);
        }
    }


    private ImpulseRealtime(
        int account,
        ImpulseConnection connection
    ) {
        this.account = account;
        this.connection = connection;
        ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(2, namedThreads("impulse-realtime-" + account));
        timers.setRemoveOnCancelPolicy(true);
        this.executor = timers;
        this.syncExecutor = new ScheduledThreadPoolExecutor(1, namedThreads("impulse-realtime-sync-" + account));
        this.syncExecutor.setRemoveOnCancelPolicy(true);
        OkHttpClient http = ImpulseConnection.httpClient()
            .newBuilder()
            .readTimeout(0L, TimeUnit.MILLISECONDS)
            .build();
        this.client = new CentrifugoClient(http, ImpulseEndpoints.realtimeUrl(), this, this, executor);
        this.client.setLogLabel("account=" + account);
        this.lanes = new ChannelLaneManager(account, client, connection);
    }


    /** Starts the periodic check that keeps the connection in line with the login and network state. */
    void start() {
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
        }
        syncExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                sync();
            }
        }, 0L, EnsureMillis, TimeUnit.MILLISECONDS);
    }


    /** Connects when logged in and online, disconnects otherwise, and keeps the user lane on the current user. Runs only on syncExecutor. */
    private void sync() {
        try {
            SyncPlanner.Plan plan = planner.plan(
                connection.currentUserId(),
                connection.hasSession(),
                ApplicationLoader.isNetworkOnline()
            );
            if (plan.dropUserLane != 0L) {
                client.unsubscribe(PublicationRouter.UserPrefix + plan.dropUserLane);
            }
            if (plan.stopLanes) {
                lanes.stop();
            }
            if (plan.disconnect) {
                client.disconnect();
            }
            if (plan.subscribeUserLane != 0L) {
                client.subscribe(PublicationRouter.UserPrefix + plan.subscribeUserLane, null);
                lanes.start();
            }
            if (plan.connect) {
                client.connect();
            }
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
    }


    /** Drops the realtime socket and syncs again, so a new socket is opened over the current route. */
    void reconnect() {
        syncExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    client.disconnect();
                } catch (RuntimeException e) {
                    FileLog.e(e);
                }
                sync();
            }
        });
    }


    /** Queues a sync now instead of waiting for the periodic check. */
    void syncSoon() {
        syncExecutor.execute(new Runnable() {
            @Override
            public void run() {
                sync();
            }
        });
    }


    /** Syncs, then skips any pending reconnect backoff so a resumed or pushed app connects at once. */
    void connectNow() {
        RemoteLog.trace(RemoteLog.ComponentRealtime, "REALTIME_CONNECT_NOW", "account", account);
        syncExecutor.execute(new Runnable() {
            @Override
            public void run() {
                sync();
                client.reconnectNow();
            }
        });
    }


    @Override
    public String fetchToken() throws IOException {
        try {
            byte[] response = connection.rpcClient().callImpulse(ImpulseRpcPaths.GetCentrifugoToken, new byte[0]);
            String token = ActorTagsProto.decodeToken(response);
            if (token == null || token.isEmpty()) {
                throw new IOException("empty centrifugo token");
            }
            return token;
        } catch (SessionLostException e) {
            RemoteLog.trace(LogLevel.WARN, RemoteLog.ComponentRealtime, "REALTIME_SESSION_LOST", "account", account, "during", "token_fetch", "error", e);
            connection.onRealtimeSessionLost();
            throw e;
        }
    }


    @Override
    public void onConnected() {
        RemoteLog.trace(RemoteLog.ComponentRealtime, "REALTIME_CONNECTED", "account", account);
    }


    @Override
    public void onSubscribed(
        String channel,
        boolean recovered,
        boolean wasRecovering
    ) {
        boolean needsDifference = gaps.onSubscribed(channel, recovered, wasRecovering);
        RemoteLog.trace(
            RemoteLog.ComponentRealtime,
            "REALTIME_SUBSCRIBED",
            "account", account,
            "lane", laneKind(channel),
            "laneId", laneId(channel),
            "recovered", recovered,
            "wasRecovering", wasRecovering,
            "getDifference", needsDifference
        );
        if (needsDifference) {
            postDifference(recovered ? "subscribe_position_dropped" : "subscribe_not_recovered", channel);
        }
        if (channel.startsWith(PublicationRouter.UserPrefix)) {
            lanes.requestRecompute();
        }
    }


    @Override
    public void onPublication(Publication publication) {
        PublicationRouter.Action action = PublicationRouter.route(publication);
        switch (action.kind) {
            case GET_DIFFERENCE:
                postDifference("publication_requests_difference", publication.channel);
                break;
            case CHANNEL_TOO_LONG:
                postChannelTooLong(action.channelId, action.pts, action.date);
                break;
            case DECODE:
                decodeAndProcess(action.proto);
                break;
            default:
                break;
        }
    }


    @Override
    public void onUnsubscribed(
        String channel,
        int code,
        String reason
    ) {
        RemoteLog.trace(
            LogLevel.WARN,
            RemoteLog.ComponentRealtime,
            "REALTIME_UNSUBSCRIBED",
            "account", account,
            "lane", laneKind(channel),
            "laneId", laneId(channel),
            "code", code
        );
        gaps.onUnsubscribed(channel, code);
    }


    @Override
    public void onDisconnected(
        int code,
        String reason,
        boolean willReconnect
    ) {
        RemoteLog.trace(
            RemoteLog.ComponentRealtime,
            "REALTIME_DISCONNECTED",
            "account", account,
            "code", code,
            "willReconnect", willReconnect
        );
    }


    private void decodeAndProcess(byte[] proto) {
        NativeByteBuffer buffer = null;
        try {
            Transcoder transcoder = connection.transcoder();
            byte[] tl = transcoder.decodeUpdates(proto, null);
            buffer = new NativeByteBuffer(tl.length);
            buffer.setDataSourceType(TLDataSourceType.NETWORK);
            buffer.writeBytes(tl);
            buffer.position(0);
            int constructor = buffer.readInt32(true);
            Object parsed = TLClassStore.Instance().TLdeserialize(buffer, constructor, true);
            if (parsed instanceof TLRPC.Updates) {
                final TLRPC.Updates updates = (TLRPC.Updates) parsed;
                KeepAliveJob.finishJob();
                Utilities.stageQueue.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        MessagesController.getInstance(account).processUpdates(updates, false);
                    }
                });
            } else {
                FileLog.e("impulse: realtime decoded an unexpected constructor 0x" + Integer.toHexString(constructor));
                postDifference("publication_unexpected_constructor", null);
            }
        } catch (Exception e) {
            FileLog.e(e);
            postDifference("publication_decode_failed", null);
        } finally {
            if (buffer != null) {
                buffer.reuse();
            }
        }
    }


    private void postChannelTooLong(
        long channelId,
        int pts,
        int date
    ) {
        TL_update.TL_updateChannelTooLong update = new TL_update.TL_updateChannelTooLong();
        update.flags = pts > 0 ? 1 : 0;
        update.channel_id = channelId;
        update.pts = pts > 0 ? pts : 0;
        final TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(update);
        updates.date = date;
        updates.seq = 0;
        Utilities.stageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                MessagesController.getInstance(account).processUpdates(updates, false);
            }
        });
    }


    private void postDifference(
        String reason,
        String channelOrNull
    ) {
        RemoteLog.trace(
            RemoteLog.ComponentSync,
            "GET_DIFFERENCE_REASON",
            "account", account,
            "reason", reason,
            "lane", channelOrNull == null ? null : laneKind(channelOrNull),
            "laneId", channelOrNull == null ? null : laneId(channelOrNull)
        );
        Utilities.stageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                MessagesController.getInstance(account).getDifference();
            }
        });
    }


    private static ThreadFactory namedThreads(final String prefix) {
        final AtomicInteger counter = new AtomicInteger(1);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }


    private static String laneKind(String channel) {
        int separator = channel.indexOf(':');
        return separator < 0 ? channel : channel.substring(0, separator);
    }


    /** The lane's numeric id as a number, so the redactor does not mistake a long id inside a string for a phone. */
    private static Object laneId(String channel) {
        int separator = channel.indexOf(':');
        if (separator < 0) {
            return null;
        }
        try {
            return Long.parseLong(channel.substring(separator + 1));
        } catch (NumberFormatException e) {
            return channel.substring(separator + 1);
        }
    }
}
