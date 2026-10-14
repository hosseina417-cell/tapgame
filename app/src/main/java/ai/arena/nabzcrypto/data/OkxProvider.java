package ai.arena.nabzcrypto.data;

import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.OrderBookMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Public OKX spot feed; no account or API key is used. */
public final class OkxProvider implements MarketProvider {
    private static final String BASE = "https://www.okx.com";

    @Override public String name() {
        return "OKX Spot";
    }

    @Override public String tradingViewExchange() {
        return "OKX";
    }

    @Override public List<MarketTicker> fetchTickers() throws ApiException {
        Object raw = HttpJson.get(BASE + "/api/v5/market/tickers?instType=SPOT");
        if (!(raw instanceof JSONObject)) throw new ApiException("پاسخ تیکر OKX نامعتبر است");
        JSONObject root = (JSONObject) raw;
        if (!"0".equals(root.optString("code"))) throw new ApiException("سرویس عمومی OKX پاسخ معتبر نداد");
        JSONArray rows = root.optJSONArray("data");
        if (rows == null) throw new ApiException("فهرست بازارهای OKX خالی است");
        long now = System.currentTimeMillis();
        List<MarketTicker> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null) continue;
            String apiSymbol = item.optString("instId", "").toUpperCase(java.util.Locale.US);
            if (!apiSymbol.endsWith("-USDT") || apiSymbol.length() <= 5) continue;
            String base = apiSymbol.substring(0, apiSymbol.length() - 5);
            double last = CandleNormalizer.number(item.optString("last"));
            double open = CandleNormalizer.number(item.optString("open24h"));
            double change = last > 0 && open > 0 ? (last / open - 1.0) * 100.0 : Double.NaN;
            MarketTicker ticker = new MarketTicker(base + "USDT", apiSymbol, base, "USDT",
                    last, change, CandleNormalizer.number(item.optString("volCcy24h")), now);
            if (ticker.isValid()) result.add(ticker);
        }
        if (result.size() < 10) throw new ApiException("تعداد بازارهای OKX کافی نیست");
        return result;
    }

    @Override public List<Candle> fetchCandles(MarketTicker ticker, int intervalMinutes, int limit)
            throws ApiException {
        String interval = intervalToken(intervalMinutes);
        if (interval == null) throw new ApiException("بازهٔ زمانی پشتیبانی نمی‌شود");
        String url = BASE + "/api/v5/market/candles?instId=" + ticker.apiSymbol
                + "&bar=" + interval + "&limit=" + Math.max(60, Math.min(300, limit + 2));
        Object raw = HttpJson.get(url);
        if (!(raw instanceof JSONObject)) throw new ApiException("پاسخ کندل OKX نامعتبر است");
        JSONObject root = (JSONObject) raw;
        if (!"0".equals(root.optString("code"))) throw new ApiException("کندل‌های OKX دریافت نشد");
        JSONArray rows = root.optJSONArray("data");
        if (rows == null) throw new ApiException("کندل‌های OKX خالی است");
        long width = intervalMinutes * 60_000L;
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 8) continue;
            if (row.length() > 8 && !"1".equals(row.optString(8))) continue;
            long openTime = parseLong(row.optString(0));
            double quoteVolume = CandleNormalizer.number(row.optString(7));
            if (!Double.isFinite(quoteVolume) || quoteVolume < 0) {
                quoteVolume = CandleNormalizer.number(row.optString(6));
            }
            candles.add(new Candle(openTime, openTime + width - 1,
                    CandleNormalizer.number(row.optString(1)),
                    CandleNormalizer.number(row.optString(2)),
                    CandleNormalizer.number(row.optString(3)),
                    CandleNormalizer.number(row.optString(4)),
                    CandleNormalizer.number(row.optString(5)), quoteVolume));
        }
        return CandleNormalizer.completed(candles, limit);
    }

    static String intervalToken(int minutes) {
        return minutes == 5 ? "5m" : minutes == 15 ? "15m"
                : minutes == 60 ? "1H" : minutes == 240 ? "4H" : null;
    }

    @Override public OrderBookMetrics fetchOrderBook(MarketTicker ticker) throws ApiException {
        Object raw = HttpJson.get(BASE + "/api/v5/market/books?instId="
                + ticker.apiSymbol + "&sz=50");
        if (!(raw instanceof JSONObject)) throw new ApiException("دفتر سفارش OKX نامعتبر است");
        JSONObject root = (JSONObject) raw;
        if (!"0".equals(root.optString("code"))) throw new ApiException("دفتر سفارش OKX دریافت نشد");
        JSONArray rows = root.optJSONArray("data");
        JSONObject data = rows == null ? null : rows.optJSONObject(0);
        OrderBookMetrics metrics = data == null ? OrderBookMetrics.unavailable()
                : OrderBookParser.parse(data.optJSONArray("bids"), data.optJSONArray("asks"),
                        ticker.lastPrice);
        if (!metrics.available) throw new ApiException("عمق بازار OKX کافی نیست");
        return metrics;
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); }
        catch (RuntimeException ignored) { return 0; }
    }
}
