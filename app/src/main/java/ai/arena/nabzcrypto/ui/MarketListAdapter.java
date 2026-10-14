package ai.arena.nabzcrypto.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import ai.arena.nabzcrypto.data.AppPreferences;
import ai.arena.nabzcrypto.model.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MarketListAdapter extends BaseAdapter {
    public interface Listener {
        /** Selecting a market opens its live TradingView symbol. */
        void onOpen(ScanResult result);
        void onDetails(ScanResult result);
        void onFavoriteChanged(ScanResult result, boolean isFavorite);
    }

    private final Context context;
    private final AppPreferences preferences;
    private final Listener listener;
    private final List<ScanResult> items = new ArrayList<>();

    public MarketListAdapter(Context context, AppPreferences preferences, Listener listener) {
        this.context = context;
        this.preferences = preferences;
        this.listener = listener;
    }

    public void setItems(List<ScanResult> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    @Override public int getCount() {
        return items.size();
    }

    @Override public ScanResult getItem(int position) {
        return items.get(position);
    }

    @Override public long getItemId(int position) {
        return items.get(position).ticker.symbol.hashCode();
    }

    @Override public boolean hasStableIds() {
        return true;
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            FrameLayout outer = new FrameLayout(context);
            outer.setPadding(Theme.dp(context, 10), Theme.dp(context, 5),
                    Theme.dp(context, 10), Theme.dp(context, 5));
            LinearLayout card = Theme.card(context);
            outer.addView(card, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            holder = build(card);
            holder.card = card;
            outer.setTag(holder);
            convertView = outer;
        } else {
            holder = (Holder) convertView.getTag();
        }

        ScanResult result = getItem(position);
        int color = SignalColors.color(result.signal);
        holder.symbol.setText(result.ticker.baseAsset);
        String rank = result.ticker.marketCapRank > 0 ? "  •  #" + result.ticker.marketCapRank : "";
        holder.quote.setText("/USDT  •  " + result.ticker.sourceCount + " منبع" + rank);
        holder.price.setText("$" + Formatters.price(result.ticker.lastPrice));
        holder.change.setText("۲۴ساعت  " + Formatters.signedPercent(result.ticker.change24hPct));
        holder.change.setTextColor(result.ticker.change24hPct >= 0 ? Theme.GREEN : Theme.RED);
        holder.signal.setText(result.signal.faLabel + "  "
                + String.format(Locale.US, "%+d", result.score));
        holder.signal.setTextColor(color);
        holder.signal.setBackground(Theme.rounded(Theme.withAlpha(color, 24),
                Theme.dp(context, 10), Theme.dp(context, 1), Theme.withAlpha(color, 90)));
        holder.metrics.setText(String.format(Locale.US,
                "۵دقیقه %+.2f%%   •   ۱۵دقیقه %+.2f%%   •   RSI %.1f   •   حجم ×%.1f   •   %s",
                result.return5mPct, result.return15mPct, result.rsi14, result.volumeRatio,
                Formatters.volume(result.ticker.quoteVolume24h)));
        String reason = result.reasons.isEmpty() ? result.signal.faDescription : result.reasons.get(0);
        holder.reason.setText(reason + (result.fromCache ? "  •  ذخیره‌شده" : ""));
        holder.favorite.setText(preferences.isFavorite(result.ticker.symbol) ? "★" : "☆");
        holder.favorite.setTextColor(preferences.isFavorite(result.ticker.symbol)
                ? Theme.AMBER : Theme.MUTED);
        holder.sparkline.setData(result.sparkline, color);
        holder.card.setContentDescription(result.ticker.displaySymbol() + "، "
                + result.signal.faLabel + "، لمس برای نمودار تریدینگ ویو");
        holder.card.setOnClickListener(v -> listener.onOpen(result));
        holder.details.setOnClickListener(v -> listener.onDetails(result));
        holder.favorite.setOnClickListener(v -> {
            boolean active = preferences.toggleFavorite(result.ticker.symbol);
            holder.favorite.setText(active ? "★" : "☆");
            holder.favorite.setTextColor(active ? Theme.AMBER : Theme.MUTED);
            listener.onFavoriteChanged(result, active);
        });
        return convertView;
    }

    private Holder build(LinearLayout card) {
        Holder holder = new Holder();
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        holder.favorite = Theme.text(context, "☆", 25, Theme.MUTED, false);
        holder.favorite.setGravity(Gravity.CENTER);
        holder.favorite.setMinWidth(Theme.dp(context, 38));
        holder.favorite.setMinHeight(Theme.dp(context, 42));
        holder.favorite.setFocusable(false);
        top.addView(holder.favorite, new LinearLayout.LayoutParams(
                Theme.dp(context, 40), Theme.dp(context, 44)));

        LinearLayout symbolBlock = new LinearLayout(context);
        symbolBlock.setOrientation(LinearLayout.VERTICAL);
        holder.symbol = Theme.text(context, "", 18, Theme.TEXT, true);
        holder.symbol.setTextDirection(View.TEXT_DIRECTION_LTR);
        holder.symbol.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        holder.quote = Theme.text(context, "", 10.5f, Theme.MUTED, false);
        holder.quote.setTextDirection(View.TEXT_DIRECTION_LTR);
        holder.quote.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        symbolBlock.addView(holder.symbol);
        symbolBlock.addView(holder.quote);
        top.addView(symbolBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));

        holder.sparkline = new SparklineView(context);
        top.addView(holder.sparkline, new LinearLayout.LayoutParams(
                Theme.dp(context, 76), Theme.dp(context, 44)));

        LinearLayout priceBlock = new LinearLayout(context);
        priceBlock.setOrientation(LinearLayout.VERTICAL);
        priceBlock.setPadding(Theme.dp(context, 8), 0, Theme.dp(context, 8), 0);
        holder.price = Theme.text(context, "", 13, Theme.TEXT, true);
        holder.price.setTextDirection(View.TEXT_DIRECTION_LTR);
        holder.price.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        holder.change = Theme.text(context, "", 10.5f, Theme.MUTED, false);
        holder.change.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR);
        holder.change.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        priceBlock.addView(holder.price);
        priceBlock.addView(holder.change);
        top.addView(priceBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        holder.signal = Theme.text(context, "", 11, Theme.CYAN, true);
        holder.signal.setGravity(Gravity.CENTER);
        holder.signal.setPadding(Theme.dp(context, 9), Theme.dp(context, 6),
                Theme.dp(context, 9), Theme.dp(context, 6));
        top.addView(holder.signal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        holder.metrics = Theme.text(context, "", 11.5f, Theme.MUTED, false);
        holder.metrics.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        holder.metrics.setPadding(0, Theme.dp(context, 10), 0, Theme.dp(context, 5));
        holder.metrics.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        card.addView(holder.metrics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        holder.reason = Theme.text(context, "", 11, Theme.TEXT, false);
        holder.reason.setMaxLines(2);
        holder.reason.setTextColor(Theme.withAlpha(Theme.TEXT, 205));
        bottom.addView(holder.reason, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        holder.details = Theme.text(context, "جزئیات", 10.5f, Theme.CYAN, true);
        holder.details.setGravity(Gravity.CENTER);
        holder.details.setPadding(Theme.dp(context, 10), Theme.dp(context, 6),
                Theme.dp(context, 10), Theme.dp(context, 6));
        holder.details.setBackground(Theme.rounded(Theme.withAlpha(Theme.CYAN, 18),
                Theme.dp(context, 10), Theme.dp(context, 1), Theme.withAlpha(Theme.CYAN, 70)));
        bottom.addView(holder.details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return holder;
    }

    private static final class Holder {
        LinearLayout card;
        TextView favorite;
        TextView symbol;
        TextView quote;
        TextView price;
        TextView change;
        TextView signal;
        TextView metrics;
        TextView reason;
        TextView details;
        SparklineView sparkline;
    }
}
