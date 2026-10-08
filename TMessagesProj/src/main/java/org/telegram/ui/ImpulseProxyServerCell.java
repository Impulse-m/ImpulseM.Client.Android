package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import net.impulsem.proxy.ProxyServer;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.Objects;


/** A server row in the style of Telegram's proxy list: radio, name, colored status line and an info button. */
public class ImpulseProxyServerCell extends FrameLayout {

    /** What one row shows; compared by value so list diffs stay cheap. */
    public static final class Row {
        public final ProxyServer server;
        public final String title;
        public final String status;
        public final int colorKey;
        public final boolean selected;


        public Row(
            ProxyServer server,
            String title,
            String status,
            int colorKey,
            boolean selected
        ) {
            this.server = server;
            this.title = title;
            this.status = status;
            this.colorKey = colorKey;
            this.selected = selected;
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
                && selected == row.selected;
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


    private final RadioButton radio;
    private final TextView nameView;
    private final TextView statusView;
    private final ImageView infoView;
    private Row row;
    private InfoListener listener;
    private boolean divider;


    public ImpulseProxyServerCell(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        int start = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        int end = LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT;
        int textLeft = LocaleController.isRTL ? 56 : 54;
        int textRight = LocaleController.isRTL ? 54 : 56;

        radio = new RadioButton(context);
        radio.setSize(dp(20));
        radio.setColor(Theme.key_radioBackground, Theme.key_radioBackgroundChecked);
        addView(radio, LayoutHelper.createFrame(22, 22, start | Gravity.CENTER_VERTICAL, 16, 0, 16, 0));

        nameView = new TextView(context);
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameView.setSingleLine(true);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setGravity(start | Gravity.CENTER_VERTICAL);
        addView(nameView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT,
            LayoutHelper.WRAP_CONTENT,
            start | Gravity.TOP,
            textLeft,
            10,
            textRight,
            0
        ));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setSingleLine(true);
        statusView.setMaxLines(1);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setGravity(start);
        addView(statusView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT,
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
        infoView.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, dp(22)));
        addView(infoView, LayoutHelper.createFrame(48, 48, end | Gravity.TOP, 8, 8, 8, 0));
        infoView.setOnClickListener(v -> {
            if (listener != null && row != null) {
                listener.onInfo(row.server);
            }
        });
    }


    public void set(
        Row newRow,
        InfoListener newListener,
        boolean showDivider
    ) {
        row = newRow;
        listener = newListener;
        divider = showDivider;
        CharSequence title = newRow.title;
        nameView.setText(Emoji.replaceEmoji(title, nameView.getPaint().getFontMetricsInt(), false));
        statusView.setText(newRow.status);
        statusView.setTextColor(Theme.getColor(newRow.colorKey));
        radio.setChecked(newRow.selected, false);
        setWillNotDraw(!divider);
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
                LocaleController.isRTL ? 0 : dp(54),
                getMeasuredHeight() - 1,
                getMeasuredWidth() - (LocaleController.isRTL ? dp(54) : 0),
                getMeasuredHeight() - 1,
                Theme.dividerPaint
            );
        }
    }
}
