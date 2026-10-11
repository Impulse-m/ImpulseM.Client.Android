package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import net.impulsem.proxy.ProxyServer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.Objects;


/** A server row in the style of Telegram's proxy list: name, colored status line with a check mark on the current server, an info button and a selection checkbox. */
public class ImpulseProxyServerCell extends FrameLayout {

    /** What one row shows; compared by value so list diffs stay cheap. */
    public static final class Row {
        public final ProxyServer server;
        public final String title;
        public final String status;
        public final int colorKey;
        public final boolean selected;
        // Whether the row can be picked in selection mode; subscription servers cannot.
        public final boolean selectable;
        // Whether the list is in selection mode.
        public final boolean selecting;
        public final boolean picked;


        public Row(
            ProxyServer server,
            String title,
            String status,
            int colorKey,
            boolean selected,
            boolean selectable,
            boolean selecting,
            boolean picked
        ) {
            this.server = server;
            this.title = title;
            this.status = status;
            this.colorKey = colorKey;
            this.selected = selected;
            this.selectable = selectable;
            this.selecting = selecting;
            this.picked = picked;
        }


        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Row)) {
                return false;
            }
            Row row = (Row) other;
            return server.id.equals(row.server.id)
                && Objects.equals(title, row.title)
                && Objects.equals(status, row.status)
                && colorKey == row.colorKey
                && selected == row.selected
                && selectable == row.selectable
                && selecting == row.selecting
                && picked == row.picked;
        }


        @Override
        public int hashCode() {
            return server.id.hashCode();
        }
    }


    public interface InfoListener {
        void onInfo(ProxyServer server);
    }


    /** A one-line colored text, used for the overall proxy status under the switch. */
    public static class StatusCell extends FrameLayout {
        private final TextView textView;


        public StatusCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setSingleLine(true);
            textView.setMaxLines(1);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 21, 0, 21, 0));
        }


        public void set(
            CharSequence text,
            int colorKey
        ) {
            textView.setText(text);
            textView.setTextColor(Theme.getColor(colorKey));
        }


        @Override
        protected void onMeasure(
            int widthMeasureSpec,
            int heightMeasureSpec
        ) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(36), MeasureSpec.EXACTLY)
            );
        }
    }


    public static class Factory extends UItem.UItemFactory<ImpulseProxyServerCell> {
        static {
            setup(new Factory());
        }


        public static UItem as(
            int id,
            Row row,
            InfoListener listener
        ) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = row;
            item.object2 = listener;
            return item;
        }


        @Override
        public ImpulseProxyServerCell createView(
            Context context,
            RecyclerListView listView,
            int currentAccount,
            int classGuid,
            Theme.ResourcesProvider resourcesProvider
        ) {
            return new ImpulseProxyServerCell(context);
        }


        @Override
        public boolean equals(
            UItem a,
            UItem b
        ) {
            return a.object instanceof Row && b.object instanceof Row
                && ((Row) a.object).server.id.equals(((Row) b.object).server.id);
        }


        @Override
        public boolean contentsEquals(
            UItem a,
            UItem b
        ) {
            return a.object != null && a.object.equals(b.object);
        }


        @Override
        public void bindView(
            View view,
            UItem item,
            boolean divider,
            UniversalAdapter adapter,
            UniversalRecyclerView listView
        ) {
            ((ImpulseProxyServerCell) view).set((Row) item.object, (InfoListener) item.object2, divider);
        }
    }


    public static class StatusFactory extends UItem.UItemFactory<StatusCell> {
        static {
            setup(new StatusFactory());
        }


        public static UItem as(
            CharSequence text,
            int colorKey
        ) {
            UItem item = UItem.ofFactory(StatusFactory.class);
            item.text = text;
            item.intValue = colorKey;
            return item;
        }


        @Override
        public StatusCell createView(
            Context context,
            RecyclerListView listView,
            int currentAccount,
            int classGuid,
            Theme.ResourcesProvider resourcesProvider
        ) {
            return new StatusCell(context);
        }


        @Override
        public void bindView(
            View view,
            UItem item,
            boolean divider,
            UniversalAdapter adapter,
            UniversalRecyclerView listView
        ) {
            ((StatusCell) view).set(item.text, item.intValue);
        }


        @Override
        public boolean isClickable() {
            return false;
        }
    }


    private final TextView nameView;
    private final TextView statusView;
    private final ImageView infoView;
    private final CheckBox2 checkBox;
    private Drawable checkDrawable;
    private ValueAnimator selectionAnimator;
    private Row row;
    private InfoListener listener;
    private boolean divider;
    private boolean selectionEnabled;


    public ImpulseProxyServerCell(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        int start = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        int end = LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT;
        int textLeft = LocaleController.isRTL ? 56 : 21;
        int textRight = LocaleController.isRTL ? 21 : 56;

        nameView = new TextView(context);
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameView.setLines(1);
        nameView.setMaxLines(1);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setGravity(start | Gravity.CENTER_VERTICAL);
        addView(nameView, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT,
            LayoutHelper.WRAP_CONTENT,
            start | Gravity.TOP,
            textLeft,
            10,
            textRight,
            0
        ));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setGravity(start);
        statusView.setLines(1);
        statusView.setMaxLines(1);
        statusView.setSingleLine(true);
        statusView.setCompoundDrawablePadding(dp(6));
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setPadding(0, 0, 0, 0);
        addView(statusView, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT,
            LayoutHelper.WRAP_CONTENT,
            start | Gravity.TOP,
            textLeft,
            35,
            textRight,
            0
        ));

        infoView = new ImageView(context);
        infoView.setImageResource(R.drawable.msg_info);
        infoView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), PorterDuff.Mode.MULTIPLY));
        infoView.setScaleType(ImageView.ScaleType.CENTER);
        infoView.setContentDescription(getString(R.string.ImpulseProxyInfo));
        addView(infoView, LayoutHelper.createFrame(48, 48, end | Gravity.TOP, 8, 8, 8, 0));
        infoView.setOnClickListener(v -> {
            if (listener != null && row != null) {
                listener.onInfo(row.server);
            }
        });

        checkBox = new CheckBox2(context, 21);
        checkBox.setColor(Theme.key_checkbox, Theme.key_radioBackground, Theme.key_checkboxCheck);
        checkBox.setDrawBackgroundAsArc(14);
        checkBox.setVisibility(GONE);
        addView(checkBox, LayoutHelper.createFrame(24, 24, start | Gravity.CENTER_VERTICAL, 16, 0, 8, 0));
    }


    public void set(
        Row newRow,
        InfoListener newListener,
        boolean showDivider
    ) {
        // The same server rebinding animates the selection changes; a recycled cell jumps to the final state.
        boolean sameServer = row != null && row.server.id.equals(newRow.server.id);
        row = newRow;
        listener = newListener;
        divider = showDivider;
        CharSequence title = newRow.title;
        nameView.setText(Emoji.replaceEmoji(title, nameView.getPaint().getFontMetricsInt(), false));
        statusView.setText(newRow.status);
        int color = Theme.getColor(newRow.colorKey);
        statusView.setTextColor(color);
        setChecked(newRow.selected, color);
        setSelectionEnabled(newRow.selecting && newRow.selectable, sameServer);
        checkBox.setChecked(newRow.picked, sameServer);
        setWillNotDraw(!divider);
    }


    // The current server gets the check mark in front of its status, tinted like the status text.
    private void setChecked(
        boolean checked,
        int color
    ) {
        if (!checked) {
            statusView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            return;
        }
        if (checkDrawable == null) {
            checkDrawable = getResources().getDrawable(R.drawable.proxy_check).mutate();
        }
        checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
        if (LocaleController.isRTL) {
            statusView.setCompoundDrawablesWithIntrinsicBounds(null, null, checkDrawable, null);
        } else {
            statusView.setCompoundDrawablesWithIntrinsicBounds(checkDrawable, null, null, null);
        }
    }


    // The same motion as Telegram's proxy row: the text slides aside and the checkbox scales in over the info button.
    private void setSelectionEnabled(
        boolean enabled,
        boolean animated
    ) {
        if (selectionEnabled == enabled && animated) {
            return;
        }
        selectionEnabled = enabled;
        if (selectionAnimator != null) {
            selectionAnimator.cancel();
            selectionAnimator = null;
        }
        float shift = LocaleController.isRTL ? -dp(32) : dp(32);
        if (!animated) {
            applySelection(enabled ? 1f : 0f, shift);
            infoView.setVisibility(enabled ? GONE : VISIBLE);
            checkBox.setVisibility(enabled ? VISIBLE : GONE);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(enabled ? 0f : 1f, enabled ? 1f : 0f).setDuration(200);
        animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        animator.addUpdateListener(animation -> applySelection((float) animation.getAnimatedValue(), shift));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (enabled) {
                    checkBox.setAlpha(0f);
                    checkBox.setVisibility(VISIBLE);
                } else {
                    infoView.setAlpha(0f);
                    infoView.setVisibility(VISIBLE);
                }
            }


            @Override
            public void onAnimationEnd(Animator animation) {
                if (selectionAnimator != animation) {
                    return;
                }
                selectionAnimator = null;
                if (enabled) {
                    infoView.setVisibility(GONE);
                } else {
                    checkBox.setVisibility(GONE);
                }
            }
        });
        selectionAnimator = animator;
        animator.start();
    }


    private void applySelection(
        float progress,
        float shift
    ) {
        float x = AndroidUtilities.lerp(0f, shift, progress);
        nameView.setTranslationX(x);
        statusView.setTranslationX(x);
        infoView.setTranslationX(x);
        checkBox.setTranslationX((LocaleController.isRTL ? dp(32) : -dp(32)) + x);

        float scale = 0.5f + progress * 0.5f;
        checkBox.setScaleX(scale);
        checkBox.setScaleY(scale);
        checkBox.setAlpha(progress);

        scale = 0.5f + (1f - progress) * 0.5f;
        infoView.setScaleX(scale);
        infoView.setScaleY(scale);
        infoView.setAlpha(1f - progress);
    }


    @Override
    protected void onMeasure(
        int widthMeasureSpec,
        int heightMeasureSpec
    ) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(64) + 1, MeasureSpec.EXACTLY)
        );
    }


    @Override
    protected void onDraw(Canvas canvas) {
        if (divider) {
            canvas.drawLine(
                LocaleController.isRTL ? 0 : dp(20),
                getMeasuredHeight() - 1,
                getMeasuredWidth() - (LocaleController.isRTL ? dp(20) : 0),
                getMeasuredHeight() - 1,
                Theme.dividerPaint
            );
        }
    }
}
