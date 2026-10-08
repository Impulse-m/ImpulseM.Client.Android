package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import net.impulsem.proxy.ProxyAdvanced;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.impulse.proxy.ProxyController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.List;


/** Advanced settings of the VLESS proxy: fragmentation, fingerprint, mux, TCP options, DNS and server checks. */
public class ImpulseProxyAdvancedActivity extends BaseFragment {

    private static final int IdFragMode = 1;
    private static final int IdFragPackets = 2;
    private static final int IdFragLength = 3;
    private static final int IdFragInterval = 4;
    private static final int IdFragFromLink = 5;
    private static final int IdFingerprint = 6;
    private static final int IdMux = 7;
    private static final int IdMuxConcurrency = 8;
    private static final int IdKeepIdle = 9;
    private static final int IdKeepInterval = 10;
    private static final int IdFastOpen = 11;
    private static final int IdMss = 12;
    private static final int IdDns = 13;
    private static final int IdPingMode = 14;
    private static final int IdPingPause = 15;
    private static final int IdPingTimeout = 16;
    private static final int IdPingUrl = 17;
    private static final int IdPingSkip = 18;
    private static final int IdReset = 19;
    private static final String PacketsExample = "1-3";
    private static final int InvalidNumber = Integer.MIN_VALUE;


    /** Edits a copy of the settings. */
    private interface Change {
        void apply(ProxyAdvanced edited);
    }


    /** Applies typed text to a copy of the settings; false means the text is invalid. */
    private interface TextChange {
        boolean apply(
            ProxyAdvanced edited,
            String text
        );
    }


    /** Applies a typed number, already range-checked, to a copy of the settings. */
    private interface NumberChange {
        void apply(
            ProxyAdvanced edited,
            int value
        );
    }


    private interface Choice {
        void chosen(int index);
    }


    private final Runnable proxyListener = new Runnable() {
        @Override
        public void run() {
            refreshList();
        }
    };
    private UniversalRecyclerView listView;
    private boolean destroyed;


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
        actionBar.setTitle(getString(R.string.ImpulseProxyAdvanced));
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

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, (item, view, position, x, y) -> false);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        return fragmentView = contentView;
    }


    private void refreshList() {
        if (!destroyed && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }


    private ProxyAdvanced current() {
        return ProxyController.getInstance().snapshot().advanced;
    }


    private void fillItems(
        ArrayList<UItem> items,
        UniversalAdapter adapter
    ) {
        ProxyAdvanced advanced = current();
        boolean fragmentOn = !ProxyAdvanced.FragmentOff.equals(advanced.fragmentMode);

        items.add(UItem.asHeader(getString(R.string.ImpulseProxyAdvFragmentation)));
        items.add(UItem.asButton(IdFragMode, getString(R.string.ImpulseProxyAdvMode), fragmentModeName(advanced.fragmentMode)));
        items.add(UItem.asButton(IdFragPackets, getString(R.string.ImpulseProxyAdvPackets), advanced.fragmentPackets).setEnabled(fragmentOn));
        items.add(UItem.asButton(IdFragLength, getString(R.string.ImpulseProxyAdvLength), advanced.fragmentLength).setEnabled(fragmentOn));
        items.add(UItem.asButton(IdFragInterval, getString(R.string.ImpulseProxyAdvInterval), advanced.fragmentInterval).setEnabled(fragmentOn));
        items.add(UItem.asCheck(IdFragFromLink, getString(R.string.ImpulseProxyAdvFromLink)).setChecked(advanced.fragmentFromLink));
        items.add(UItem.asShadow(getString(R.string.ImpulseProxyAdvFragmentInfo)));

        items.add(UItem.asHeader(getString(R.string.ImpulseProxyAdvFingerprint)));
        items.add(UItem.asButton(
            IdFingerprint,
            getString(R.string.ImpulseProxyAdvFingerprint),
            advanced.fingerprint.isEmpty() ? getString(R.string.ImpulseProxyAdvAsInLink) : advanced.fingerprint
        ));
        items.add(UItem.asShadow(getString(R.string.ImpulseProxyAdvFingerprintInfo)));

        items.add(UItem.asHeader(getString(R.string.ImpulseProxyAdvMultiplexing)));
        items.add(UItem.asCheck(IdMux, getString(R.string.ImpulseProxyAdvMux)).setChecked(advanced.muxEnabled));
        items.add(UItem.asButton(IdMuxConcurrency, getString(R.string.ImpulseProxyAdvMuxConcurrency), String.valueOf(advanced.muxConcurrency)));
        items.add(UItem.asShadow(getString(R.string.ImpulseProxyAdvMuxInfo)));

        items.add(UItem.asHeader(getString(R.string.ImpulseProxyAdvTcp)));
        items.add(UItem.asButton(IdKeepIdle, getString(R.string.ImpulseProxyAdvKeepIdle), systemOr(advanced.keepAliveIdle)));
        items.add(UItem.asButton(IdKeepInterval, getString(R.string.ImpulseProxyAdvKeepInterval), systemOr(advanced.keepAliveInterval)));
        items.add(UItem.asCheck(IdFastOpen, getString(R.string.ImpulseProxyAdvFastOpen)).setChecked(advanced.tcpFastOpen));
        items.add(UItem.asButton(IdMss, getString(R.string.ImpulseProxyAdvMss), systemOr(advanced.tcpMaxSeg)));
        items.add(UItem.asShadow(getString(R.string.ImpulseProxyAdvTcpInfo)));

        items.add(UItem.asHeader(getString(R.string.ImpulseProxyAdvDns)));
        items.add(UItem.asButton(IdDns, getString(R.string.ImpulseProxyAdvDnsServer), dnsName(advanced)));
        items.add(UItem.asShadow(getString(R.string.ImpulseProxyAdvDnsInfo)));

        items.add(UItem.asHeader(getString(R.string.ImpulseProxyAdvChecks)));
        items.add(UItem.asButton(IdPingMode, getString(R.string.ImpulseProxyAdvMode), pingModeName(advanced.pingMode)));
        items.add(UItem.asButton(IdPingPause, getString(R.string.ImpulseProxyAdvPause), String.valueOf(advanced.pingPauseMillis)));
        items.add(UItem.asButton(IdPingTimeout, getString(R.string.ImpulseProxyAdvTimeout), String.valueOf(advanced.pingTimeoutSeconds)));
        items.add(UItem.asButton(IdPingUrl, getString(R.string.ImpulseProxyAdvUrl), advanced.pingUrl));
        items.add(UItem.asCheck(IdPingSkip, getString(R.string.ImpulseProxyAdvSkipConnected)).setChecked(advanced.pingSkipWhileConnected));
        items.add(UItem.asShadow(getString(R.string.ImpulseProxyAdvChecksInfo)));

        items.add(UItem.asButton(IdReset, getString(R.string.ImpulseProxyAdvReset)).red());
        items.add(UItem.asShadow(""));
    }


    private void onClick(
        UItem item,
        View view,
        int position,
        float x,
        float y
    ) {
        ProxyAdvanced advanced = current();
        boolean fragmentOn = !ProxyAdvanced.FragmentOff.equals(advanced.fragmentMode);
        switch (item.id) {
            case IdFragMode:
                chooseFragmentMode(advanced);
                break;
            case IdFragPackets:
                if (fragmentOn) {
                    choosePackets(advanced);
                }
                break;
            case IdFragLength:
                if (fragmentOn) {
                    askText(
                        getString(R.string.ImpulseProxyAdvLength),
                        advanced.fragmentLength,
                        false,
                        getString(R.string.ImpulseProxyAdvRangeError),
                        (edited, text) -> {
                            edited.fragmentLength = text;
                            return ProxyAdvanced.isRange(text);
                        }
                    );
                }
                break;
            case IdFragInterval:
                if (fragmentOn) {
                    askText(
                        getString(R.string.ImpulseProxyAdvInterval),
                        advanced.fragmentInterval,
                        false,
                        getString(R.string.ImpulseProxyAdvRangeError),
                        (edited, text) -> {
                            edited.fragmentInterval = text;
                            return ProxyAdvanced.isRange(text);
                        }
                    );
                }
                break;
            case IdFragFromLink:
                change(edited -> edited.fragmentFromLink = !edited.fragmentFromLink);
                break;
            case IdFingerprint:
                chooseFingerprint(advanced);
                break;
            case IdMux:
                change(edited -> edited.muxEnabled = !edited.muxEnabled);
                break;
            case IdMuxConcurrency:
                askNumber(
                    getString(R.string.ImpulseProxyAdvMuxConcurrency),
                    advanced.muxConcurrency,
                    1,
                    128,
                    (edited, value) -> edited.muxConcurrency = value
                );
                break;
            case IdKeepIdle:
                askNumber(
                    getString(R.string.ImpulseProxyAdvKeepIdle),
                    advanced.keepAliveIdle,
                    0,
                    86400,
                    (edited, value) -> edited.keepAliveIdle = value
                );
                break;
            case IdKeepInterval:
                askNumber(
                    getString(R.string.ImpulseProxyAdvKeepInterval),
                    advanced.keepAliveInterval,
                    0,
                    86400,
                    (edited, value) -> edited.keepAliveInterval = value
                );
                break;
            case IdFastOpen:
                change(edited -> edited.tcpFastOpen = !edited.tcpFastOpen);
                break;
            case IdMss:
                askText(
                    getString(R.string.ImpulseProxyAdvMss),
                    String.valueOf(advanced.tcpMaxSeg),
                    true,
                    getString(R.string.ImpulseProxyAdvMssError),
                    (edited, text) -> {
                        int value = parseNumber(text, 0, 1460);
                        if (value == InvalidNumber || (value != 0 && value < 64)) {
                            return false;
                        }
                        edited.tcpMaxSeg = value;
                        return true;
                    }
                );
                break;
            case IdDns:
                chooseDns(advanced);
                break;
            case IdPingMode:
                choosePingMode(advanced);
                break;
            case IdPingPause:
                askNumber(
                    getString(R.string.ImpulseProxyAdvPause),
                    advanced.pingPauseMillis,
                    0,
                    10000,
                    (edited, value) -> edited.pingPauseMillis = value
                );
                break;
            case IdPingTimeout:
                askNumber(
                    getString(R.string.ImpulseProxyAdvTimeout),
                    advanced.pingTimeoutSeconds,
                    1,
                    30,
                    (edited, value) -> edited.pingTimeoutSeconds = value
                );
                break;
            case IdPingUrl:
                askText(
                    getString(R.string.ImpulseProxyAdvUrl),
                    advanced.pingUrl,
                    false,
                    getString(R.string.ImpulseProxyAdvUrlError),
                    (edited, text) -> {
                        if (!text.startsWith("https://") || text.length() <= "https://".length() || text.indexOf(' ') >= 0) {
                            return false;
                        }
                        edited.pingUrl = text;
                        return true;
                    }
                );
                break;
            case IdPingSkip:
                change(edited -> edited.pingSkipWhileConnected = !edited.pingSkipWhileConnected);
                break;
            case IdReset:
                confirmReset();
                break;
            default:
                break;
        }
    }


    private void chooseFragmentMode(ProxyAdvanced advanced) {
        String[] modes = {ProxyAdvanced.FragmentOff, ProxyAdvanced.FragmentClassic, ProxyAdvanced.FragmentFinalMask};
        String[] names = {
            getString(R.string.ImpulseProxyAdvOff),
            getString(R.string.ImpulseProxyAdvClassic),
            getString(R.string.ImpulseProxyAdvFinalMask)
        };
        choose(getString(R.string.ImpulseProxyAdvMode), names, indexOf(modes, advanced.fragmentMode), index -> {
            change(edited -> edited.fragmentMode = modes[index]);
        });
    }


    private void choosePackets(ProxyAdvanced advanced) {
        String[] names = {
            ProxyAdvanced.DefaultFragmentPackets,
            PacketsExample,
            getString(R.string.ImpulseProxyAdvCustom)
        };
        int selected = 2;
        if (ProxyAdvanced.DefaultFragmentPackets.equals(advanced.fragmentPackets)) {
            selected = 0;
        } else if (PacketsExample.equals(advanced.fragmentPackets)) {
            selected = 1;
        }
        choose(getString(R.string.ImpulseProxyAdvPackets), names, selected, index -> {
            if (index == 0) {
                change(edited -> edited.fragmentPackets = ProxyAdvanced.DefaultFragmentPackets);
            } else if (index == 1) {
                change(edited -> edited.fragmentPackets = PacketsExample);
            } else {
                askText(
                    getString(R.string.ImpulseProxyAdvPackets),
                    advanced.fragmentPackets,
                    false,
                    getString(R.string.ImpulseProxyAdvPacketsError),
                    (edited, text) -> {
                        edited.fragmentPackets = text;
                        return ProxyAdvanced.isPackets(text);
                    }
                );
            }
        });
    }


    private void chooseFingerprint(ProxyAdvanced advanced) {
        List<String> names = new ArrayList<String>();
        names.add(getString(R.string.ImpulseProxyAdvAsInLink));
        names.addAll(ProxyAdvanced.Fingerprints);
        int selected = advanced.fingerprint.isEmpty() ? 0 : ProxyAdvanced.Fingerprints.indexOf(advanced.fingerprint) + 1;
        choose(getString(R.string.ImpulseProxyAdvFingerprint), names.toArray(new String[0]), selected, index -> {
            change(edited -> edited.fingerprint = index == 0 ? "" : ProxyAdvanced.Fingerprints.get(index - 1));
        });
    }


    private void chooseDns(ProxyAdvanced advanced) {
        String[] modes = {ProxyAdvanced.DnsSystem, ProxyAdvanced.DnsCloudflare, ProxyAdvanced.DnsGoogle, ProxyAdvanced.DnsCustom};
        String[] names = {
            getString(R.string.ImpulseProxyAdvSystem),
            getString(R.string.ImpulseProxyAdvDnsCloudflare),
            getString(R.string.ImpulseProxyAdvDnsGoogle),
            getString(R.string.ImpulseProxyAdvCustom)
        };
        choose(getString(R.string.ImpulseProxyAdvDnsServer), names, indexOf(modes, advanced.dnsMode), index -> {
            if (index < 3) {
                change(edited -> edited.dnsMode = modes[index]);
            } else {
                askText(
                    getString(R.string.ImpulseProxyAdvDnsServer),
                    advanced.dnsCustom,
                    false,
                    getString(R.string.ImpulseProxyAdvDnsError),
                    (edited, text) -> {
                        if (!ProxyAdvanced.isDnsServer(text)) {
                            return false;
                        }
                        edited.dnsMode = ProxyAdvanced.DnsCustom;
                        edited.dnsCustom = text;
                        return true;
                    }
                );
            }
        });
    }


    private void choosePingMode(ProxyAdvanced advanced) {
        String[] modes = {ProxyAdvanced.PingBatch, ProxyAdvanced.PingSequential};
        String[] names = {
            getString(R.string.ImpulseProxyAdvChecksBatch),
            getString(R.string.ImpulseProxyAdvChecksOne)
        };
        choose(getString(R.string.ImpulseProxyAdvMode), names, indexOf(modes, advanced.pingMode), index -> {
            change(edited -> edited.pingMode = modes[index]);
        });
    }


    private void choose(
        String title,
        String[] names,
        int selected,
        Choice choice
    ) {
        Activity activity = getParentActivity();
        if (activity == null || destroyed) {
            return;
        }
        showDialog(AlertsCreator.createSingleChoiceDialog(activity, names, title, selected, (dialog, which) -> choice.chosen(which)));
    }


    private void askNumber(
        String title,
        int initial,
        int min,
        int max,
        NumberChange numberChange
    ) {
        askText(
            title,
            String.valueOf(initial),
            true,
            LocaleController.formatString(R.string.ImpulseProxyAdvNumberError, min, max),
            (edited, text) -> {
                int value = parseNumber(text, min, max);
                if (value == InvalidNumber) {
                    return false;
                }
                numberChange.apply(edited, value);
                return true;
            }
        );
    }


    /** Asks for text; on OK an invalid value shows errorText and keeps the old setting. */
    private void askText(
        String title,
        String initial,
        boolean numeric,
        String errorText,
        TextChange textChange
    ) {
        Context context = getParentActivity();
        if (context == null || destroyed) {
            return;
        }
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(8), dp(24), 0);

        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        field.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setBackgroundDrawable(Theme.createEditTextDrawable(context, true));
        field.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setSingleLine(true);
        field.setPadding(0, dp(8), 0, dp(8));
        field.setText(initial);
        field.setSelection(field.length());
        container.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourceProvider);
        builder.setTitle(title);
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.OK), (dialog, which) -> {
            String text = field.getText() == null ? "" : field.getText().toString().trim();
            ProxyAdvanced edited = current().copy();
            if (textChange.apply(edited, text)) {
                save(edited);
            } else {
                toast(errorText);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
        AndroidUtilities.runOnUIThread(() -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        }, 100);
    }


    private void confirmReset() {
        Context context = getParentActivity();
        if (context == null || destroyed) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourceProvider);
        builder.setTitle(getString(R.string.ImpulseProxyAdvResetTitle));
        builder.setPositiveButton(getString(R.string.ImpulseProxyAdvResetButton), (dialog, which) -> save(new ProxyAdvanced()));
        builder.makeRed(AlertDialog.BUTTON_POSITIVE);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }


    private void change(Change change) {
        ProxyAdvanced edited = current().copy();
        change.apply(edited);
        save(edited);
    }


    private void save(ProxyAdvanced edited) {
        edited.sanitize();
        ProxyController.getInstance().update(next -> next.advanced = edited);
        refreshList();
    }


    private String fragmentModeName(String mode) {
        if (ProxyAdvanced.FragmentClassic.equals(mode)) {
            return getString(R.string.ImpulseProxyAdvClassic);
        }
        if (ProxyAdvanced.FragmentFinalMask.equals(mode)) {
            return getString(R.string.ImpulseProxyAdvFinalMask);
        }
        return getString(R.string.ImpulseProxyAdvOff);
    }


    private String pingModeName(String mode) {
        return ProxyAdvanced.PingSequential.equals(mode)
            ? getString(R.string.ImpulseProxyAdvChecksOne)
            : getString(R.string.ImpulseProxyAdvChecksBatch);
    }


    private String dnsName(ProxyAdvanced advanced) {
        if (ProxyAdvanced.DnsCloudflare.equals(advanced.dnsMode)) {
            return getString(R.string.ImpulseProxyAdvDnsCloudflare);
        }
        if (ProxyAdvanced.DnsGoogle.equals(advanced.dnsMode)) {
            return getString(R.string.ImpulseProxyAdvDnsGoogle);
        }
        if (ProxyAdvanced.DnsCustom.equals(advanced.dnsMode)) {
            return advanced.dnsCustom;
        }
        return getString(R.string.ImpulseProxyAdvSystem);
    }


    private String systemOr(int value) {
        return value == 0 ? getString(R.string.ImpulseProxyAdvSystem) : String.valueOf(value);
    }


    private static int indexOf(
        String[] values,
        String value
    ) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }


    /** Digits only, within min..max; InvalidNumber otherwise. */
    private static int parseNumber(
        String text,
        int min,
        int max
    ) {
        if (text.isEmpty() || text.length() > 9) {
            return InvalidNumber;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return InvalidNumber;
            }
        }
        int value = Integer.parseInt(text);
        return value >= min && value <= max ? value : InvalidNumber;
    }


    private void toast(String message) {
        Context context = destroyed ? null : getParentActivity();
        if (context != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }
}
