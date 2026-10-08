package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.JsonArray;

import net.impulsem.proxy.InputKind;
import net.impulsem.proxy.LibXrayClient;
import net.impulsem.proxy.OutboundFilter;
import net.impulsem.proxy.ProxyAdvanced;
import net.impulsem.proxy.ProxyServer;
import net.impulsem.proxy.ProxyState;
import net.impulsem.proxy.Subscription;
import net.impulsem.proxy.XrayConfigBuilder;
import net.impulsem.proxy.XrayException;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.impulse.proxy.ProxyController;
import org.telegram.tgnet.impulse.proxy.ProxyErrors;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/** The VLESS proxy screen: manual links, subscriptions and the on/off switches. */
public class ImpulseProxyActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int IdUse = 1;
    private static final int IdUseForCalls = 2;
    private static final int IdAdd = 3;
    private static final int IdRefreshPing = 4;
    private static final int IdInfo = 5;
    private static final int IdAdvanced = 6;
    private static final int IdDynamicStart = 100;
    private static final long PingIntervalMs = 60000L;
    private static final int LogLimit = 120;
    private static final Pattern HexRun = Pattern.compile("[0-9a-fA-F]{8,}");
    private static final Pattern UuidLike = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final Pattern LongToken = Pattern.compile("[A-Za-z0-9+/_=-]{40,}");
    private static final Pattern ShareLink = Pattern.compile("vless://\\S+");
    private static final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ImpulseProxyScreen");
        thread.setDaemon(true);
        return thread;
    });


    private static final class SubscriptionAction {
        final Subscription subscription;
        final boolean delete;


        SubscriptionAction(
            Subscription subscription,
            boolean delete
        ) {
            this.subscription = subscription;
            this.delete = delete;
        }
    }


    private final Map<String, Long> delays = new HashMap<String, Long>();
    // The delays the list is ordered by; refreshed only on the first result and on explicit checks, so periodic ticks never reshuffle rows.
    private final Map<String, Long> sortDelays = new HashMap<String, Long>();
    private final Set<String> pending = new HashSet<String>();
    private final Map<Integer, Object> targets = new HashMap<Integer, Object>();
    private final Set<String> refreshing = new HashSet<String>();
    private final Runnable proxyListener = new Runnable() {
        @Override
        public void run() {
            if (!destroyed && listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        }
    };
    private final ImpulseProxyServerCell.InfoListener infoListener = this::showServerInfo;
    private final Runnable pingTick = new Runnable() {
        @Override
        public void run() {
            // A round still running means the next tick comes later; ticks never queue rounds.
            if (!pinging) {
                autoPing(false);
            }
            scheduleTick();
        }
    };
    private UniversalRecyclerView listView;
    private boolean pinging;
    private boolean pingAgain;
    private boolean pingAgainMarkAll;
    private static final ExecutorService converter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ImpulseProxyConvert");
        thread.setDaemon(true);
        return thread;
    });
    // Read by the ping loop on the background thread.
    private volatile boolean destroyed;
    private long lastPingAt;
    private int currentConnectionState;
    private int nextId;


    @Override
    public boolean onFragmentCreate() {
        currentConnectionState = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
        ProxyController.getInstance().addListener(proxyListener);
        return super.onFragmentCreate();
    }


    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        AndroidUtilities.cancelRunOnUIThread(pingTick);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        ProxyController.getInstance().removeListener(proxyListener);
        super.onFragmentDestroy();
    }


    @Override
    public void onResume() {
        super.onResume();
        if (!pinging && System.currentTimeMillis() - lastPingAt >= PingIntervalMs) {
            autoPing(sortDelays.isEmpty());
        }
        scheduleTick();
    }


    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(pingTick);
    }


    @Override
    public void didReceivedNotification(
        int id,
        int account,
        Object... args
    ) {
        if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (state != currentConnectionState) {
                currentConnectionState = state;
                proxyListener.run();
            }
        }
    }


    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.ImpulseProxyTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == IdRefreshPing) {
                    pingServers(true, true);
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(IdRefreshPing, R.drawable.msg_retry).setContentDescription(getString(R.string.ImpulseProxyCheckServers));

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, (item, view, position, x, y) -> false);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        return fragmentView = contentView;
    }


    private int register(Object target) {
        int id = IdDynamicStart + nextId++;
        targets.put(id, target);
        return id;
    }


    private void fillItems(
        ArrayList<UItem> items,
        UniversalAdapter adapter
    ) {
        targets.clear();
        nextId = 0;
        ProxyController controller = ProxyController.getInstance();
        ProxyState state = controller.snapshot();

        items.add(UItem.asCheck(IdUse, getString(R.string.ImpulseProxyUse)).setChecked(state.enabled));
        items.add(overallStatusItem(controller, state));
        items.add(UItem.asCheck(IdUseForCalls, getString(R.string.ImpulseProxyUseForCalls))
            .setChecked(state.useForCalls)
            .setEnabled(state.enabled));
        items.add(UItem.asButton(IdAdvanced, getString(R.string.ImpulseProxyAdvanced)));
        items.add(UItem.asShadow(""));

        items.add(UItem.asHeader(getString(R.string.ImpulseProxyMyLinks)));
        for (ProxyServer server : sorted(state.manual)) {
            items.add(serverItem(server, state));
        }
        items.add(UItem.asButton(IdAdd, R.drawable.msg_add, getString(R.string.ImpulseProxyAdd)).accent());
        items.add(UItem.asShadow(""));

        for (Subscription subscription : state.subscriptions) {
            items.add(UItem.asHeader(subscriptionTitle(subscription)));
            addSubscriptionInfo(items, subscription);
            for (ProxyServer server : sorted(subscription.servers)) {
                items.add(serverItem(server, state));
            }
            boolean busy = refreshing.contains(subscription.id);
            items.add(UItem.asButton(register(new SubscriptionAction(subscription, false)), R.drawable.msg_retry, getString(R.string.ImpulseProxyRefresh))
                .setEnabled(!busy));
            items.add(UItem.asButton(register(new SubscriptionAction(subscription, true)), R.drawable.msg_delete, getString(R.string.ImpulseProxyDelete))
                .red());
            items.add(UItem.asShadow(""));
        }
    }


    private UItem overallStatusItem(
        ProxyController controller,
        ProxyState state
    ) {
        if (!state.enabled) {
            return ImpulseProxyServerCell.StatusFactory.as(getString(R.string.ImpulseProxyStatusOff), Theme.key_windowBackgroundWhiteGrayText2);
        }
        ProxyController.Status status = controller.status();
        if (status == ProxyController.Status.FAILED) {
            String error = controller.lastError();
            String reason = TextUtils.isEmpty(error) ? getString(R.string.ImpulseProxyErrorCoreError) : describeError(error);
            return ImpulseProxyServerCell.StatusFactory.as(LocaleController.formatString(R.string.ImpulseProxyStatusError, reason), Theme.key_text_RedRegular);
        }
        if (isConnected(controller)) {
            return ImpulseProxyServerCell.StatusFactory.as(getString(R.string.ImpulseProxyConnected), Theme.key_windowBackgroundWhiteGreenText);
        }
        return ImpulseProxyServerCell.StatusFactory.as(getString(R.string.ImpulseProxyConnecting), Theme.key_windowBackgroundWhiteGrayText2);
    }


    // The same rule the chat-list item uses: the core runs and Telegram itself is connected through it.
    private boolean isConnected(ProxyController controller) {
        return controller.status() == ProxyController.Status.RUNNING
            && (currentConnectionState == ConnectionsManager.ConnectionStateConnected || currentConnectionState == ConnectionsManager.ConnectionStateUpdating);
    }


    private UItem serverItem(
        ProxyServer server,
        ProxyState state
    ) {
        ProxyController controller = ProxyController.getInstance();
        boolean selected = server.id.equals(state.selectedId);
        String status;
        int colorKey;
        Long delay = delays.get(server.id);
        boolean skipping = state.advanced.pingSkipWhileConnected && state.enabled && controller.status() == ProxyController.Status.RUNNING;
        if (selected && state.enabled && controller.status() != ProxyController.Status.FAILED) {
            if (isConnected(controller)) {
                status = getString(R.string.ImpulseProxyConnected);
                colorKey = Theme.key_windowBackgroundWhiteBlueText6;
            } else {
                status = getString(R.string.ImpulseProxyConnecting);
                colorKey = Theme.key_windowBackgroundWhiteGrayText2;
            }
        } else if (pending.contains(server.id) || (delay == null && !skipping)) {
            status = getString(R.string.ImpulseProxyChecking);
            colorKey = Theme.key_windowBackgroundWhiteGrayText2;
        } else if (delay == null) {
            // Automatic checks are off while connected, so there is nothing to wait for.
            status = getString(R.string.ImpulseProxyNotChecked);
            colorKey = Theme.key_windowBackgroundWhiteGrayText2;
        } else if (delay >= 0) {
            status = LocaleController.formatString(R.string.ImpulseProxyAvailable, delay.intValue());
            colorKey = Theme.key_windowBackgroundWhiteGreenText;
        } else {
            status = getString(R.string.ImpulseProxyUnavailable);
            colorKey = Theme.key_text_RedRegular;
        }
        ImpulseProxyServerCell.Row row = new ImpulseProxyServerCell.Row(server, displayName(server), status, colorKey, selected);
        return ImpulseProxyServerCell.Factory.as(register(server), row, infoListener);
    }


    private static String displayName(ProxyServer server) {
        if (server.name == null || server.name.trim().isEmpty()) {
            return server.host + ":" + server.port;
        }
        return server.name.trim();
    }


    private static String transportText(ProxyServer server) {
        StringBuilder text = new StringBuilder();
        if (!"none".equals(server.security)) {
            text.append(server.security.toUpperCase()).append(" · ");
        }
        // Xray reports plain TCP as "raw".
        text.append("raw".equals(server.network) ? "TCP" : server.network.toUpperCase());
        return text.toString();
    }


    private String subscriptionTitle(Subscription subscription) {
        if (subscription.meta != null && !TextUtils.isEmpty(subscription.meta.title)) {
            return subscription.meta.title;
        }
        String host = null;
        try {
            host = Uri.parse(subscription.url).getHost();
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
        return TextUtils.isEmpty(host) ? subscription.url : host;
    }


    private void addSubscriptionInfo(
        ArrayList<UItem> items,
        Subscription subscription
    ) {
        // total is 0 or -1 when the panel reports no limit: show only the expiry date then.
        if (subscription.meta != null && subscription.meta.total <= 0 && subscription.meta.expire > 0) {
            items.add(infoItem(DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(subscription.meta.expire * 1000L)), false));
        }
        if (subscription.meta != null && subscription.meta.total > 0) {
            long used = Math.max(0L, subscription.meta.upload) + Math.max(0L, subscription.meta.download);
            String total = AndroidUtilities.formatFileSize(subscription.meta.total);
            String usedText = AndroidUtilities.formatFileSize(used);
            String text;
            if (subscription.meta.expire > 0) {
                String until = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(subscription.meta.expire * 1000L));
                text = LocaleController.formatString(R.string.ImpulseProxyTraffic, usedText, total, until);
            } else {
                text = usedText + " / " + total;
            }
            items.add(infoItem(text, false));
        }
        if (subscription.skipped > 0) {
            items.add(infoItem(LocaleController.formatString(R.string.ImpulseProxySkipped, subscription.skipped), false));
        }
        if (subscription.updatedAt > 0) {
            String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(subscription.updatedAt));
            items.add(infoItem(LocaleController.formatString(R.string.ImpulseProxyUpdated, when), false));
        }
        if (!TextUtils.isEmpty(subscription.lastError)) {
            items.add(infoItem(describeError(subscription.lastError), true));
        }
    }


    private UItem infoItem(
        CharSequence text,
        boolean error
    ) {
        UItem item = UItem.asButton(IdInfo, text).setEnabled(false);
        if (error) {
            item.red();
        }
        return item;
    }


    private List<ProxyServer> sorted(List<ProxyServer> servers) {
        List<ProxyServer> result = new ArrayList<ProxyServer>(servers);
        if (sortDelays.isEmpty()) {
            return result;
        }
        Collections.sort(result, new Comparator<ProxyServer>() {
            @Override
            public int compare(
                ProxyServer a,
                ProxyServer b
            ) {
                int rankA = rank(a);
                int rankB = rank(b);
                if (rankA != rankB) {
                    return Integer.compare(rankA, rankB);
                }
                if (rankA == 0) {
                    return Long.compare(sortDelays.get(a.id), sortDelays.get(b.id));
                }
                return 0;
            }
        });
        return result;
    }


    private int rank(ProxyServer server) {
        Long delay = sortDelays.get(server.id);
        if (delay == null) {
            return 1;
        }
        return delay >= 0 ? 0 : 2;
    }


    private void onClick(
        UItem item,
        View view,
        int position,
        float x,
        float y
    ) {
        ProxyController controller = ProxyController.getInstance();
        if (item.id == IdUse) {
            ProxyState state = controller.snapshot();
            if (!state.enabled && state.selected() == null) {
                toast(getString(R.string.ImpulseProxyNoSelection));
                return;
            }
            controller.update(next -> next.enabled = !next.enabled);
            return;
        }
        if (item.id == IdUseForCalls) {
            controller.update(next -> next.useForCalls = !next.useForCalls);
            return;
        }
        if (item.id == IdAdd) {
            showAddDialog();
            return;
        }
        if (item.id == IdAdvanced) {
            presentFragment(new ImpulseProxyAdvancedActivity());
            return;
        }
        Object target = targets.get(item.id);
        if (target instanceof ProxyServer) {
            String id = ((ProxyServer) target).id;
            controller.update(next -> next.selectedId = id);
        } else if (target instanceof SubscriptionAction) {
            SubscriptionAction action = (SubscriptionAction) target;
            String id = action.subscription.id;
            if (action.delete) {
                boolean active = false;
                ProxyServer selected = controller.snapshot().selected();
                if (selected != null) {
                    for (ProxyServer candidate : action.subscription.servers) {
                        if (candidate.id.equals(selected.id)) {
                            active = true;
                            break;
                        }
                    }
                }
                confirmDelete(
                    getString(R.string.ImpulseProxyDeleteSubscriptionTitle),
                    LocaleController.formatString(R.string.ImpulseProxyDeleteSubscriptionText, subscriptionTitle(action.subscription)),
                    active ? getString(R.string.ImpulseProxyDeleteActiveWarning) : null,
                    () -> controller.update(next -> next.removeSubscription(id))
                );
            } else {
                refresh(id);
            }
        }
    }


    /** Asks before a destructive delete; activeWarning (null when the target isn't selected) is shown while the proxy is on. */
    private void confirmDelete(
        String title,
        String message,
        String activeWarning,
        Runnable onConfirm
    ) {
        Context context = getParentActivity();
        if (context == null || destroyed) {
            return;
        }
        String text = message;
        if (activeWarning != null && ProxyController.getInstance().isEnabled()) {
            text = message + "\n\n" + activeWarning;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourceProvider);
        builder.setTitle(title);
        builder.setMessage(text);
        builder.setPositiveButton(getString(R.string.ImpulseProxyDelete), (dialog, which) -> onConfirm.run());
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.redPositive();
    }


    private void showServerInfo(ProxyServer server) {
        Context context = getParentActivity();
        if (context == null || destroyed) {
            return;
        }
        // A subscription server comes back on the next refresh, so only manual servers can be deleted.
        boolean manual = false;
        for (ProxyServer candidate : ProxyController.getInstance().snapshot().manual) {
            if (candidate.id.equals(server.id)) {
                manual = true;
                break;
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourceProvider);
        builder.setTitle(displayName(server));
        builder.setMessage(LocaleController.formatString(R.string.ImpulseProxyAddress, server.host, server.port) + "\n" + transportText(server));
        if (!TextUtils.isEmpty(server.shareLink)) {
            builder.setPositiveButton(getString(R.string.ImpulseProxyCopyLink), (dialog, which) -> {
                AndroidUtilities.addToClipboard(server.shareLink);
            });
        }
        if (manual) {
            builder.setNegativeButton(getString(R.string.ImpulseProxyDelete), (dialog, which) -> {
                confirmDelete(
                    getString(R.string.ImpulseProxyDeleteServerTitle),
                    LocaleController.formatString(R.string.ImpulseProxyDeleteServerText, displayName(server)),
                    server.id.equals(ProxyController.getInstance().snapshot().selectedId) ? getString(R.string.ImpulseProxyDeleteActiveServerWarning) : null,
                    () -> ProxyController.getInstance().update(next -> next.removeServer(server.id))
                );
            });
        }
        builder.setNeutralButton(getString(R.string.ImpulseProxyClose), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        if (manual) {
            TextView deleteButton = (TextView) dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            if (deleteButton != null) {
                deleteButton.setTextColor(getThemedColor(Theme.key_text_RedBold));
            }
        }
    }


    private void refresh(String subscriptionId) {
        refreshing.add(subscriptionId);
        proxyListener.run();
        ProxyController.getInstance().refresh(subscriptionId, error -> {
            refreshing.remove(subscriptionId);
            if (destroyed) {
                return;
            }
            if (error != null) {
                toast(describeError(error));
            } else {
                autoPing(true);
            }
            proxyListener.run();
        });
    }


    private void scheduleTick() {
        AndroidUtilities.cancelRunOnUIThread(pingTick);
        AndroidUtilities.runOnUIThread(pingTick, PingIntervalMs);
    }


    /** An automatic round (open, tick, after add or refresh); skipped while connected when the user asked for that. */
    private void autoPing(boolean resort) {
        ProxyController controller = ProxyController.getInstance();
        ProxyState state = controller.snapshot();
        if (state.advanced.pingSkipWhileConnected && state.enabled && controller.status() == ProxyController.Status.RUNNING) {
            return;
        }
        pingServers(false, resort);
    }


    /**
     * Pings every server off the UI thread. markAll shows "Checking" on rows that already have a result;
     * resort lets this round's results reorder the list.
     */
    private void pingServers(
        boolean markAll,
        boolean resort
    ) {
        if (destroyed) {
            return;
        }
        if (pinging) {
            // Whatever asked while a round runs must still get its own round afterwards.
            pingAgain = true;
            pingAgainMarkAll |= markAll;
            return;
        }
        List<ProxyServer> servers = new ArrayList<ProxyServer>();
        Set<String> seen = new HashSet<String>();
        ProxyState snapshot = ProxyController.getInstance().snapshot();
        // Read once per round, so a settings change mid-round applies from the next round.
        ProxyAdvanced advanced = snapshot.advanced;
        int chunkSize = ProxyAdvanced.PingSequential.equals(advanced.pingMode) ? 1 : LibXrayClient.MaxPingBatch;
        // The selected server goes first so its status shows up first.
        ProxyServer selected = snapshot.selected();
        if (selected != null && seen.add(selected.id)) {
            servers.add(selected);
        }
        for (ProxyServer server : snapshot.allServers()) {
            if (seen.add(server.id)) {
                servers.add(server);
            }
        }
        if (servers.isEmpty()) {
            return;
        }
        pinging = true;
        lastPingAt = System.currentTimeMillis();
        for (ProxyServer server : servers) {
            if (markAll || !delays.containsKey(server.id)) {
                pending.add(server.id);
            }
        }
        proxyListener.run();
        background.execute(() -> {
            Map<String, Long> result = new HashMap<String, Long>();
            try {
                List<ProxyServer> valid = new ArrayList<ProxyServer>();
                List<String> configs = new ArrayList<String>();
                for (ProxyServer server : servers) {
                    try {
                        configs.add(XrayConfigBuilder.pingConfig(server, advanced));
                        valid.add(server);
                    } catch (RuntimeException e) {
                        result.put(server.id, -1L);
                        FileLog.d("impulse proxy: ping config failed, server " + safe(server.name) + " (" + server.host + "), " + e.getClass().getSimpleName());
                    }
                }
                ProxyController.getInstance().xray().pingInChunks(
                    configs,
                    XrayConfigBuilder.ProxyTag,
                    advanced.pingUrl,
                    advanced.pingTimeoutSeconds,
                    chunkSize,
                    advanced.pingPauseMillis,
                    (offset, measured) -> {
                        Map<String, Long> chunk = new HashMap<String, Long>();
                        for (int i = 0; i < measured.length && offset + i < valid.size(); i++) {
                            ProxyServer server = valid.get(offset + i);
                            LibXrayClient.PingResult ping = measured[i];
                            chunk.put(server.id, ping.delay);
                            result.put(server.id, ping.delay);
                            if (ping.delay < 0) {
                                FileLog.d("impulse proxy: ping failed, server " + safe(server.name) + " (" + server.host + "), " + safe(ping.error));
                            }
                        }
                        AndroidUtilities.runOnUIThread(() -> {
                            if (destroyed) {
                                return;
                            }
                            for (String id : chunk.keySet()) {
                                pending.remove(id);
                            }
                            delays.putAll(chunk);
                            proxyListener.run();
                        });
                        return !destroyed;
                    }
                );
            } catch (Throwable e) {
                // Throwable, not RuntimeException: a native Error from libXray must not leave the ping state stuck.
                FileLog.d("impulse proxy: ping round failed, " + e.getClass().getSimpleName());
            }
            // Always posted, so a failed round can never leave the ping state stuck.
            AndroidUtilities.runOnUIThread(() -> {
                pinging = false;
                if (destroyed) {
                    return;
                }
                for (ProxyServer server : servers) {
                    if (!result.containsKey(server.id)) {
                        result.put(server.id, -1L);
                    }
                    pending.remove(server.id);
                }
                delays.putAll(result);
                if (resort || sortDelays.isEmpty()) {
                    sortDelays.clear();
                    sortDelays.putAll(delays);
                }
                proxyListener.run();
                if (pingAgain) {
                    boolean again = pingAgainMarkAll;
                    pingAgain = false;
                    pingAgainMarkAll = false;
                    pingServers(again, true);
                }
            });
        });
    }


    /** Log text must never carry a UUID, key material or a share link: strip those and cap the length. */
    private static String safe(String text) {
        if (text == null) {
            return "";
        }
        String clean = ShareLink.matcher(text).replaceAll("");
        clean = UuidLike.matcher(clean).replaceAll("");
        clean = LongToken.matcher(clean).replaceAll("");
        clean = HexRun.matcher(clean).replaceAll("");
        clean = clean.replace('\n', ' ').trim();
        return clean.length() > LogLimit ? clean.substring(0, LogLimit) : clean;
    }


    private void showAddDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(8), dp(24), 0);

        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setHint(getString(R.string.ImpulseProxyAddHint));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        field.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setBackgroundDrawable(Theme.createEditTextDrawable(context, true));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        field.setMaxLines(4);
        field.setPadding(0, dp(8), 0, dp(8));
        container.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView paste = linkButton(context, getString(R.string.ImpulseProxyPaste));
        paste.setOnClickListener(v -> {
            String text = clipboardText();
            if (text != null) {
                field.setText(text.trim());
                field.setSelection(field.length());
            }
        });
        TextView scan = linkButton(context, getString(R.string.ImpulseProxyScanQr));
        scan.setOnClickListener(v -> {
            CameraScanActivity.showAsSheet(this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
                @Override
                public void didFindQr(String text) {
                    if (text != null) {
                        field.setText(text.trim());
                        field.setSelection(field.length());
                    }
                }
            });
        });
        buttons.addView(paste, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        buttons.addView(scan, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        container.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourceProvider);
        builder.setTitle(getString(R.string.ImpulseProxyAdd));
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.OK), (dialog, which) -> {
            addInput(field.getText() == null ? "" : field.getText().toString());
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }


    private TextView linkButton(
        Context context,
        String text
    ) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setTextColor(getThemedColor(Theme.key_dialogTextBlue));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(4), dp(10), dp(4), dp(10));
        button.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        return button;
    }


    private String clipboardText() {
        try {
            ClipboardManager clipboard = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(ApplicationLoader.applicationContext);
                return text == null ? null : text.toString();
            }
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
        return null;
    }


    private void addInput(String text) {
        InputKind kind = InputKind.detect(text);
        if (kind == InputKind.SUBSCRIPTION) {
            boolean[] cancelled = new boolean[1];
            AlertDialog progress = showSpinner(cancelled);
            ProxyController.getInstance().addSubscription(text.trim(), error -> {
                dismissSpinner(progress);
                if (destroyed) {
                    return;
                }
                if (error != null) {
                    if (!cancelled[0]) {
                        toast(describeError(error));
                    }
                } else {
                    autoPing(true);
                }
            });
        } else if (kind == InputKind.LINKS) {
            addLinks(text);
        } else {
            toast(getString(R.string.ImpulseProxyInvalid));
        }
    }


    private AlertDialog showSpinner(boolean[] cancelled) {
        Context context = getParentActivity();
        if (context == null) {
            return null;
        }
        AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        progress.setOnCancelListener(dialog -> cancelled[0] = true);
        progress.show();
        return progress;
    }


    private void dismissSpinner(AlertDialog progress) {
        if (progress == null) {
            return;
        }
        try {
            progress.dismiss();
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
    }


    private void addLinks(String text) {
        boolean[] cancelled = new boolean[1];
        AlertDialog progress = showSpinner(cancelled);
        converter.execute(() -> {
            List<ProxyServer> servers = new ArrayList<ProxyServer>();
            for (String raw : text.split("\\r?\\n")) {
                String line = raw.trim();
                if (!line.toLowerCase().startsWith("vless://")) {
                    continue;
                }
                try {
                    JsonArray outbounds = ProxyController.getInstance().xray().convertShareLinks(line);
                    for (ProxyServer server : OutboundFilter.filter(outbounds).servers) {
                        servers.add(server.withShareLink(line));
                    }
                } catch (XrayException e) {
                    FileLog.d("impulse proxy: link conversion failed");
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                dismissSpinner(progress);
                if (destroyed) {
                    return;
                }
                if (servers.isEmpty()) {
                    if (!cancelled[0]) {
                        toast(getString(R.string.ImpulseProxyInvalid));
                    }
                } else {
                    ProxyController.getInstance().update(next -> next.addManual(servers));
                    autoPing(true);
                }
            });
        });
    }


    /** Maps a controller error code to localized text; anything that is not a known code is shown as-is. */
    public static String describeError(String error) {
        String name = ProxyErrors.nameOf(error);
        if (name == null) {
            return error;
        }
        int argument = ProxyErrors.argumentOf(error);
        switch (name) {
            case ProxyErrors.NotRunning:
                return getString(R.string.ImpulseProxyErrorNotRunning);
            case ProxyErrors.InvalidSubscription:
                return getString(R.string.ImpulseProxyErrorInvalidSubscription);
            case ProxyErrors.SubscriptionNotFound:
                return getString(R.string.ImpulseProxyErrorSubscriptionNotFound);
            case ProxyErrors.NoLinks:
                return argument > 0
                    ? LocaleController.formatString(R.string.ImpulseProxyErrorNoLinksSkipped, argument)
                    : getString(R.string.ImpulseProxyErrorNoLinks);
            case ProxyErrors.Http:
                return LocaleController.formatString(R.string.ImpulseProxyErrorHttp, argument);
            case ProxyErrors.Network:
                return getString(R.string.ImpulseProxyErrorNetwork);
            case ProxyErrors.InvalidConfig:
                return getString(R.string.ImpulseProxyErrorInvalidConfig);
            case ProxyErrors.CoreStart:
                return getString(R.string.ImpulseProxyErrorCoreStart);
            case ProxyErrors.CoreError:
                return getString(R.string.ImpulseProxyErrorCoreError);
            case ProxyErrors.CoreStopped:
                return getString(R.string.ImpulseProxyErrorCoreStopped);
            case ProxyErrors.NoServer:
                return getString(R.string.ImpulseProxyNoSelection);
            default:
                return error;
        }
    }


    private void toast(String message) {
        Context context = destroyed ? null : getParentActivity();
        if (context != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }
}
