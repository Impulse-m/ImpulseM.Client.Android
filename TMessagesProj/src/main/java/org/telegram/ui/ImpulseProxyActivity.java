package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import net.impulsem.proxy.OutboundFilter;
import net.impulsem.proxy.ProxyServer;
import net.impulsem.proxy.ProxyState;
import net.impulsem.proxy.Subscription;
import net.impulsem.proxy.XrayConfigBuilder;
import net.impulsem.proxy.XrayException;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.impulse.proxy.ProxyController;
import org.telegram.tgnet.impulse.proxy.ProxyErrors;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/** The VLESS proxy screen: manual links, subscriptions and the on/off switches. */
public class ImpulseProxyActivity extends BaseFragment {

    private static final int IdUse = 1;
    private static final int IdUseForCalls = 2;
    private static final int IdAdd = 3;
    private static final int IdCheckAll = 4;
    private static final int IdInfo = 5;
    private static final int IdDynamicStart = 100;
    private static final String PingUrl = "https://www.gstatic.com/generate_204";
    private static final int PingTimeoutSeconds = 5;
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
    private UniversalRecyclerView listView;
    private boolean checking;
    private boolean destroyed;
    private int nextId;


    @Override
    public boolean onFragmentCreate() {
        ProxyController.getInstance().addListener(proxyListener);
        return super.onFragmentCreate();
    }


    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        ProxyController.getInstance().removeListener(proxyListener);
        super.onFragmentDestroy();
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
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
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
        String coreError = controller.lastError();
        if (state.enabled && controller.status() == ProxyController.Status.FAILED && !TextUtils.isEmpty(coreError)) {
            items.add(infoItem(describeError(coreError), true));
        }
        items.add(UItem.asCheck(IdUseForCalls, getString(R.string.ImpulseProxyUseForCalls))
            .setChecked(state.useForCalls)
            .setEnabled(state.enabled));
        items.add(UItem.asShadow(""));

        items.add(UItem.asHeader(getString(R.string.ImpulseProxyMyLinks)));
        for (ProxyServer server : sorted(state.manual)) {
            items.add(serverItem(server, state.selectedId));
        }
        items.add(UItem.asButton(IdAdd, R.drawable.msg_add, getString(R.string.ImpulseProxyAdd)).accent());
        items.add(UItem.asShadow(""));

        for (Subscription subscription : state.subscriptions) {
            items.add(UItem.asHeader(subscriptionTitle(subscription)));
            addSubscriptionInfo(items, subscription);
            for (ProxyServer server : sorted(subscription.servers)) {
                items.add(serverItem(server, state.selectedId));
            }
            boolean busy = refreshing.contains(subscription.id);
            items.add(UItem.asButton(register(new SubscriptionAction(subscription, false)), R.drawable.msg_retry, getString(R.string.ImpulseProxyRefresh))
                .setEnabled(!busy));
            items.add(UItem.asButton(register(new SubscriptionAction(subscription, true)), R.drawable.msg_delete, getString(R.string.ImpulseProxyDelete))
                .red());
            items.add(UItem.asShadow(""));
        }

        if (!state.allServers().isEmpty()) {
            items.add(UItem.asButton(IdCheckAll, R.drawable.msg_retry, getString(R.string.ImpulseProxyCheckAll))
                .setEnabled(!checking));
            items.add(UItem.asShadow(""));
        }
    }


    private UItem serverItem(
        ProxyServer server,
        String selectedId
    ) {
        return UItem.asRadio(register(server), server.name, serverSubtitle(server))
            .setChecked(server.id.equals(selectedId));
    }


    private String serverSubtitle(ProxyServer server) {
        StringBuilder text = new StringBuilder();
        text.append(server.host).append(':').append(server.port).append(" · ");
        if (!"none".equals(server.security)) {
            text.append(server.security.toUpperCase()).append(" · ");
        }
        // Xray reports plain TCP as "raw".
        text.append("raw".equals(server.network) ? "TCP" : server.network.toUpperCase());
        Long delay = delays.get(server.id);
        if (delay != null) {
            text.append(" · ");
            if (delay >= 0) {
                text.append(LocaleController.formatString(R.string.ImpulseProxyPingMs, delay.intValue()));
            } else {
                text.append(getString(R.string.ImpulseProxyUnavailable));
            }
        }
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
        if (delays.isEmpty()) {
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
                    return Long.compare(delays.get(a.id), delays.get(b.id));
                }
                return 0;
            }
        });
        return result;
    }


    private int rank(ProxyServer server) {
        Long delay = delays.get(server.id);
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
        if (item.id == IdCheckAll) {
            checkAll();
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
                controller.update(next -> next.removeSubscription(id));
            } else {
                refresh(id);
            }
        }
    }


    private boolean onLongClick(
        UItem item,
        View view,
        int position,
        float x,
        float y
    ) {
        Object target = targets.get(item.id);
        if (!(target instanceof ProxyServer)) {
            return false;
        }
        ProxyServer server = (ProxyServer) target;
        ProxyState state = ProxyController.getInstance().snapshot();
        boolean manual = false;
        for (ProxyServer candidate : state.manual) {
            if (candidate.id.equals(server.id)) {
                manual = true;
                break;
            }
        }
        // A subscription server comes back on the next refresh, so only the subscription can be deleted.
        if (!manual) {
            return false;
        }
        ItemOptions options = ItemOptions.makeOptions(this, view);
        if (!TextUtils.isEmpty(server.shareLink)) {
            options.add(R.drawable.msg_copy, getString(R.string.ImpulseProxyCopyLink), () -> {
                AndroidUtilities.addToClipboard(server.shareLink);
            });
        }
        options.add(R.drawable.msg_delete, getString(R.string.ImpulseProxyDelete), true, () -> {
            ProxyController.getInstance().update(next -> next.removeServer(server.id));
        });
        options.show();
        return true;
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
            }
            proxyListener.run();
        });
    }


    private void checkAll() {
        if (checking) {
            return;
        }
        List<ProxyServer> servers = new ArrayList<ProxyServer>();
        Set<String> seen = new HashSet<String>();
        for (ProxyServer server : ProxyController.getInstance().snapshot().allServers()) {
            if (seen.add(server.id)) {
                servers.add(server);
            }
        }
        if (servers.isEmpty()) {
            return;
        }
        checking = true;
        proxyListener.run();
        background.execute(() -> {
            Map<String, Long> result = new HashMap<String, Long>();
            List<ProxyServer> valid = new ArrayList<ProxyServer>();
            List<String> configs = new ArrayList<String>();
            for (ProxyServer server : servers) {
                try {
                    configs.add(XrayConfigBuilder.pingConfig(server));
                    valid.add(server);
                } catch (RuntimeException e) {
                    result.put(server.id, -1L);
                }
            }
            try {
                long[] measured = ProxyController.getInstance().xray().pingBatch(
                    configs,
                    XrayConfigBuilder.ProxyTag,
                    PingUrl,
                    PingTimeoutSeconds
                );
                for (int i = 0; i < valid.size(); i++) {
                    result.put(valid.get(i).id, i < measured.length ? measured[i] : -1L);
                }
            } catch (XrayException e) {
                FileLog.d("impulse proxy: ping failed");
                for (ProxyServer server : valid) {
                    result.put(server.id, -1L);
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) {
                    return;
                }
                delays.putAll(result);
                checking = false;
                proxyListener.run();
            });
        });
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
            ProxyController.getInstance().addSubscription(text.trim(), error -> {
                if (error != null) {
                    toast(describeError(error));
                }
            });
        } else if (kind == InputKind.LINKS) {
            addLinks(text);
        } else {
            toast(getString(R.string.ImpulseProxyInvalid));
        }
    }


    private void addLinks(String text) {
        background.execute(() -> {
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
                if (servers.isEmpty()) {
                    toast(getString(R.string.ImpulseProxyInvalid));
                } else {
                    ProxyController.getInstance().update(next -> next.addManual(servers));
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
