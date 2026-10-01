package org.telegram.tgnet.impulse;

import net.impulsem.transport.auth.ImpulseRpcPaths;
import net.impulsem.transport.realtime.ActorTagsProto;
import net.impulsem.transport.realtime.CentrifugoClient;
import net.impulsem.transport.realtime.Debouncer;
import net.impulsem.transport.realtime.LaneSelector;
import net.impulsem.transport.realtime.PublicationRouter;
import net.impulsem.transport.rpc.SessionLostException;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;


/**
 * Keeps the channel lanes of one account in line with the chat list: the open chat plus the most recently active
 * channel dialogs, at most {@link LaneSelector#MaxLanes} in total. Each lane is subscribed with the account's
 * actor tag, which the backend uses to keep the account's own actions out of the lane.
 */
final class ChannelLaneManager implements NotificationCenter.NotificationCenterDelegate {

    private static final long DebounceMillis = 500L;
    private static final long RetryMillis = 10L * 1000L;


    private final int account;
    private final CentrifugoClient client;
    private final ImpulseConnection connection;
    private final ScheduledThreadPoolExecutor executor;
    private final Debouncer debouncer;
    private final Object lock = new Object();
    private final Map<Long, String> actorTags = new HashMap<>();

    private volatile long openedChannelId;
    private boolean observing;
    private boolean active;
    private boolean retryScheduled;


    ChannelLaneManager(
        int account,
        CentrifugoClient client,
        ImpulseConnection connection
    ) {
        this.account = account;
        this.client = client;
        this.connection = connection;
        this.executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "impulse-lanes-" + ChannelLaneManager.this.account);
                thread.setDaemon(true);
                return thread;
            }
        });
        this.executor.setRemoveOnCancelPolicy(true);
        this.debouncer = new Debouncer(executor, DebounceMillis, new Runnable() {
            @Override
            public void run() {
                snapshot();
            }
        });
    }


    /** Begins following the chat list. Safe to call repeatedly. */
    void start() {
        synchronized (lock) {
            if (active) {
                return;
            }
            active = true;
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (!active || observing) {
                        return;
                    }
                    observing = true;
                }
                NotificationCenter.getInstance(account).addObserver(ChannelLaneManager.this, NotificationCenter.dialogsNeedReload);
            }
        });
        debouncer.trigger();
    }


    /** Drops every channel lane and forgets the cached tags, for logout and account changes. */
    void stop() {
        synchronized (lock) {
            if (!active) {
                return;
            }
            active = false;
            actorTags.clear();
        }
        debouncer.cancel();
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (active || !observing) {
                        return;
                    }
                    observing = false;
                }
                NotificationCenter.getInstance(account).removeObserver(ChannelLaneManager.this, NotificationCenter.dialogsNeedReload);
            }
        });
        for (String channel : client.subscribedChannels()) {
            if (channel.startsWith(PublicationRouter.ChannelPrefix)) {
                client.unsubscribe(channel);
            }
        }
    }


    void requestRecompute() {
        debouncer.trigger();
    }


    /** UI thread. */
    void onChatOpened(long dialogId) {
        openedChannelId = channelOf(dialogId);
        debouncer.trigger();
    }


    /** UI thread. */
    void onChatClosed(long dialogId) {
        if (dialogId < 0L && openedChannelId == -dialogId) {
            openedChannelId = 0L;
            debouncer.trigger();
        }
    }


    @Override
    public void didReceivedNotification(
        int id,
        int account,
        Object... args
    ) {
        if (id == NotificationCenter.dialogsNeedReload && account == this.account) {
            debouncer.trigger();
        }
    }


    private long channelOf(long dialogId) {
        if (dialogId >= 0L) {
            return 0L;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        if (chat != null && ChatObject.isChannel(chat)) {
            return -dialogId;
        }
        return 0L;
    }


    /** Reads the dialog list on the UI thread, where it is mutated, then continues on the lane thread. */
    private void snapshot() {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                final List<LaneSelector.Candidate> candidates = new ArrayList<>();
                try {
                    MessagesController controller = MessagesController.getInstance(account);
                    List<TLRPC.Dialog> dialogs = controller.getAllDialogs();
                    for (int a = 0; a < dialogs.size(); a++) {
                        TLRPC.Dialog dialog = dialogs.get(a);
                        if (dialog == null || dialog.id >= 0L) {
                            continue;
                        }
                        TLRPC.Chat chat = controller.getChat(-dialog.id);
                        if (chat == null || !ChatObject.isChannel(chat) || ChatObject.isNotInChat(chat)) {
                            continue;
                        }
                        candidates.add(new LaneSelector.Candidate(-dialog.id, dialog.last_message_date));
                    }
                } catch (RuntimeException e) {
                    FileLog.e(e);
                    return;
                }
                final long opened = openedChannelId;
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        apply(candidates, opened);
                    }
                });
            }
        });
    }


    private void apply(
        List<LaneSelector.Candidate> candidates,
        long opened
    ) {
        synchronized (lock) {
            if (!active) {
                return;
            }
        }
        try {
            Set<Long> desired = LaneSelector.select(candidates, opened, LaneSelector.MaxLanes);
            Set<Long> current = new HashSet<>();
            for (String channel : client.subscribedChannels()) {
                long id = PublicationRouter.channelId(channel);
                if (id > 0L) {
                    current.add(id);
                }
            }
            for (Long id : current) {
                if (!desired.contains(id)) {
                    client.unsubscribe(PublicationRouter.ChannelPrefix + id);
                }
            }
            List<Long> missingTags = new ArrayList<>();
            for (Long id : desired) {
                if (!current.contains(id) && !hasTag(id)) {
                    missingTags.add(id);
                }
            }
            if (!missingTags.isEmpty()) {
                fetchTags(missingTags);
            }
            boolean failed = false;
            for (Long id : desired) {
                if (current.contains(id)) {
                    continue;
                }
                String tag;
                synchronized (lock) {
                    tag = actorTags.get(id);
                }
                if (tag == null) {
                    failed = true;
                    continue;
                }
                synchronized (lock) {
                    if (!active) {
                        return;
                    }
                }
                client.subscribe(PublicationRouter.ChannelPrefix + id, tag);
            }
            if (failed) {
                scheduleRetry();
            }
        } catch (SessionLostException e) {
            connection.onRealtimeSessionLost();
        } catch (IOException e) {
            log("actor tags failed: " + e);
            scheduleRetry();
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
    }


    private boolean hasTag(long id) {
        synchronized (lock) {
            return actorTags.containsKey(id);
        }
    }


    private void fetchTags(List<Long> ids) throws IOException {
        for (List<Long> batch : ActorTagsProto.batches(ids, ActorTagsProto.MaxBatch)) {
            byte[] response = connection.rpcClient().callImpulse(
                ImpulseRpcPaths.GetChannelActorTags,
                ActorTagsProto.encodeRequest(batch)
            );
            Map<Long, String> tags = ActorTagsProto.decodeResponse(response);
            synchronized (lock) {
                actorTags.putAll(tags);
            }
        }
    }


    private void scheduleRetry() {
        synchronized (lock) {
            if (retryScheduled || !active) {
                return;
            }
            retryScheduled = true;
        }
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    retryScheduled = false;
                }
                debouncer.trigger();
            }
        }, RetryMillis, TimeUnit.MILLISECONDS);
    }


    private static void log(String message) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("impulse lanes: " + message);
        }
    }
}
