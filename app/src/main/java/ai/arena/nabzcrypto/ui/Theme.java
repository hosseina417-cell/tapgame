package ai.arena.nabzcrypto.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Theme {
    public static final int BG = Color.rgb(7, 17, 31);
    public static final int SURFACE = Color.rgb(16, 29, 45);
    public static final int SURFACE_2 = Color.rgb(23, 40, 61);
    public static final int BORDER = Color.rgb(35, 55, 78);
    public static final int TEXT = Color.rgb(243, 247, 250);
    public static final int MUTED = Color.rgb(147, 164, 184);
    public static final int CYAN = Color.rgb(24, 200, 200);
    public static final int GREEN = Color.rgb(45, 218, 140);
    public static final int RED = Color.rgb(255, 92, 108);
    public static final int AMBER = Color.rgb(255, 182, 72);
    public static final int BLUE = Color.rgb(85, 145, 255);

    private static Typeface regular;
    private static Typeface bold;

    private Theme() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static Typeface regular(Context context) {
        if (regular == null) {
            try {
                regular = Typeface.createFromAsset(context.getAssets(), "Vazirmatn-Regular.ttf");
            } catch (RuntimeException ignored) {
                regular = Typeface.create("sans", Typeface.NORMAL);
            }
        }
        return regular;
    }

    public static Typeface bold(Context context) {
        if (bold == null) {
            try {
                bold = Typeface.createFromAsset(context.getAssets(), "Vazirmatn-Bold.ttf");
            } catch (RuntimeException ignored) {
                bold = Typeface.create("sans", Typeface.BOLD);
            }
        }
        return bold;
    }

    public static TextView text(Context context, String value, float sizeSp, int color, boolean isBold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(isBold ? bold(context) : regular(context));
        view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0, 1.08f);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        return view;
    }

    public static TextView button(Context context, String value, int background, int foreground) {
        TextView button = text(context, value, 13, foreground, true);
        button.setGravity(Gravity.CENTER);
        int horizontal = dp(context, 14);
        button.setPadding(horizontal, dp(context, 9), horizontal, dp(context, 9));
        button.setMinHeight(dp(context, 44));
        GradientDrawable content = rounded(background, dp(context, 13), 0, Color.TRANSPARENT);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(withAlpha(foreground, 45)), content, null));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    public static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = dp(context, 14);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(rounded(SURFACE, dp(context, 18), dp(context, 1), BORDER));
        return card;
    }

    public static GradientDrawable rounded(int fill, float radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static View spacer(Context context, int heightDp) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, heightDp)));
        return spacer;
    }
}
