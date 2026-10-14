package ai.arena.nabzcrypto.data;

import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.OrderBookMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class KucoinProvider implements MarketProvider {
    private static final String BASE = "https://api.kucoin.com";

    @Override public String name() {
        return "KuCoin Spot";
    }

    @Override public String tradingViewExchange() {
        return "KUCOIN";
    }

    @Override public List<MarketTicker> fetchTickers() throws ApiException {
        Object raw = HttpJson.get(BASE + "/api/v1/market/allTickers");
        if (!(raw instanceof JSONObject)) throw new ApiException("پاسخ تیکر کوکوین نامعتبر است");
        JSONObject root = (JSONObject) raw;
        if (!"200000".equals(root.optString("code"))) {
            throw new ApiException("سرویس عمومی کوکوین پاسخ معتبر نداد");
        }
        JSONArray rows = root.optJSONObject("data") == null ? null
                : root.optJSONObject("data").optJSONArray("ticker");
        if (rows == null) throw new ApiException("فهرست بازارهای کوکوین خالی است");
        long now = System.currentTimeMillis();
        List<MarketTicker> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null) continue;
            String apiSymbol = item.optString("symbol", "").toUpperCase(java.util.Locale.US);
            if (!apiSymbol.endsWith("-USDT") || apiSymbol.length() <= 5) continue;
            String base = apiSymbol.substring(0, apiSymbol.length() - 5);
            double fractionalChange = CandleNormalizer.number(item.optString("changeRate"));
            MarketTicker ticker = new MarketTicker(
                    base + "USDT", apiSymbol, base, "USDT",
                    CandleNormalizer.number(item.optString("last")),
                    fractionalChange * 100.0,
                    CandleNormalizer.number(item.optString("volValue")), now);
            if (ticker.isValid()) result.add(ticker);
        }
        if (result.size() < 10) throw new ApiException("تعداد بازارهای کوکوین کافی نیست");
        return result;
    }

    @Override public List<Candle> fetchCandles(MarketTicker ticker, int intervalMinutes, int limit)
            throws ApiException {
        String type = intervalToken(intervalMinutes);
        if (type == null) throw new ApiException("بازهٔ زمانی پشتیبانی نمی‌شود");
        long nowSec = System.currentTimeMillis() / 1000L;
        long startSec = nowSec - (long) intervalMinutes * 60L * (limit + 3L);
        String url = BASE + "/api/v1/market/candles?type=" + type + "&symbol=" + ticker.apiSymbol
                + "&startAt=" + startSec + "&endAt=" + nowSec;
        Object raw = HttpJson.get(url);
        if (!(raw instanceof JSONObject)) throw new ApiException("پاسخ کندل کوکوین نامعتبر است");
        JSONObject root = (JSONObject) raw;
        if (!"200000".equals(root.optString("code"))) {
            throw new ApiException("کندل‌های کوکوین دریافت نشد");
        }
        JSONArray rows = root.optJSONArray("data");
        if (rows == null) throw new ApiException("کندل‌های کوکوین خالی است");
        List<Candle> candles = new ArrayList<>();
        long widthMs = intervalMinutes * 60_000L;
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 7) continue;
            long openTime = row.optLong(0) * 1000L;
            candles.add(new Candle(
                    openTime, openTime + widthMs - 1,
                    CandleNormalizer.number(row.optString(1)),
                    CandleNormalizer.number(row.optString(3)),
                    CandleNormalizer.number(row.optString(4)),
                    CandleNormalizer.number(row.optString(2)),
                    CandleNormalizer.number(row.optString(5)),
                    CandleNormalizer.number(row.optString(6))));
        }
        return CandleNormalizer.completed(candles, limit);
    }

    static String intervalToken(int minutes) {
        return minutes == 5 ? "5min" : minutes == 15 ? "15min"
                : minutes == 60 ? "1hour" : minutes == 240 ? "4hour" : null;
    }

    @Override public OrderBookMetrics fetchOrderBook(MarketTicker ticker) throws ApiException {
        Object raw = HttpJson.get(BASE + "/api/v1/market/orderbook/level2_100?symbol="
                + ticker.apiSymbol);
        if (!(raw instanceof JSONObject)) throw new ApiException("دفتر سفارش کوکوین نامعتبر است");
        JSONObject root = (JSONObject) raw;
        if (!"200000".equals(root.optString("code"))) {
            throw new ApiException("دفتر سفارش کوکوین دریافت نشد");
        }
        JSONObject data = root.optJSONObject("data");
        OrderBookMetrics metrics = data == null ? OrderBookMetrics.unavailable()
                : OrderBookParser.parse(data.optJSONArray("bids"), data.optJSONArray("asks"),
                        ticker.lastPrice);
        if (!metrics.available) throw new ApiException("عمق بازار کوکوین کافی نیست");
        return metrics;
    }
}
