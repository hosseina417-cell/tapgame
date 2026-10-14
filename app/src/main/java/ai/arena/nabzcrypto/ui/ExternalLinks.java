package ai.arena.nabzcrypto.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import ai.arena.nabzcrypto.model.ScanResult;

/** Opens official external pages without scraping or embedding private APIs. */
public final class ExternalLinks {
    private ExternalLinks() {}

    public static boolean openTradingView(Activity activity, ScanResult result) {
        String exchange = tradingViewExchange(result.source);
        String chartSymbol = exchange + ":" + result.ticker.baseAsset + "USDT";
        Uri uri = Uri.parse("https://www.tradingview.com/chart/").buildUpon()
                .appendQueryParameter("symbol", chartSymbol)
                .appendQueryParameter("interval", "15")
                .build();
        Intent appIntent = browsable(uri);
        appIntent.setPackage("com.tradingview.tradingviewapp");
        try {
            activity.startActivity(appIntent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return open(activity, uri);
        }
    }

    public static boolean openCoinMarketCap(Activity activity, String baseAsset) {
        Uri uri = Uri.parse("https://coinmarketcap.com/search/").buildUpon()
                .appendQueryParameter("q", baseAsset)
                .build();
        return open(activity, uri);
    }

    public static boolean openCoinGecko(Activity activity, String baseAsset) {
        Uri uri = Uri.parse("https://www.coingecko.com/en/search").buildUpon()
                .appendQueryParameter("query", baseAsset)
                .build();
        return open(activity, uri);
    }

    public static boolean openGoogle(Activity activity, String baseAsset) {
        Uri uri = Uri.parse("https://www.google.com/search").buildUpon()
                .appendQueryParameter("q", baseAsset + " USDT crypto price news")
                .build();
        return open(activity, uri);
    }

    private static boolean open(Activity activity, Uri uri) {
        try {
            activity.startActivity(browsable(uri));
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    private static Intent browsable(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        return intent;
    }

    private static String tradingViewExchange(String source) {
        String value = source == null ? "" : source.toUpperCase(java.util.Locale.US);
        if (value.contains("KUCOIN")) return "KUCOIN";
        if (value.contains("BYBIT")) return "BYBIT";
        if (value.contains("OKX")) return "OKX";
        return "BINANCE";
    }
}
