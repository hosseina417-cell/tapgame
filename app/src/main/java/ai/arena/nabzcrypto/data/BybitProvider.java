package ai.arena.nabzcrypto.data;

import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.OrderBookMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Public Bybit spot feed; no account or API key is used. */
public final class BybitProvider implements MarketProvider {
    private static final String BASE = "https://api.bybit.com";

    @Override public String name() {
        return "Bybit Spot";
    }

    @Override public String tradingViewExchange() {
        return "BYBIT";
    }

    @Override public List<MarketTicker> fetchTickers() throws ApiException {
        Object raw = HttpJson.get(BASE + "/v5/market/tickers?category=spot");
        if (!(raw instanceof JSONObject)) throw new ApiException("پاسخ تیکر بای‌بیت نامعتبر است");
        JSONObject root = (JSONObject) raw;
        if (root.optInt("retCode", -1) != 0) throw new ApiException("سرویس عمومی بای‌بیت پاسخ معتبر نداد");
        JSONObject resultObject = root.optJSONObject("result");
        JSONArray rows = resultObject == null ? null : resultObject.optJSONArray("list");
        if (rows == null) throw new ApiException("فهرست بازارهای بای‌بیت خالی است");
        long now = System.currentTimeMillis();
        List<MarketTicker> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null) continue;
            String symbol = item.optString("symbol", "").toUpperCase(java.util.Locale.US);
            if (!symbol.endsWith("USDT") || symbol.length() <= 4) continue;
            String base = symbol.substring(0, symbol.length() - 4);
            double fractionalChange = CandleNormalizer.number(item.optString("price24hPcnt"));
            MarketTicker ticker = new MarketTicker(symbol, symbol, base, "USDT",
                    CandleNormalizer.number(item.optString("lastPrice")),
                    fractionalChange * 100.0,
                    CandleNormalizer.number(item.optString("turnover24h")), now);
            if (ticker.isValid()) result.add(ticker);
        }
        if (result.size() < 10) throw new ApiException("تعداد بازارهای بای‌بیت کافی نیست");
        return result;
    }

    @Override public List<Candle> fetchCandles(MarketTicker ticker, int intervalMinutes, int limit)
            throws ApiException {
        String interval = intervalToken(intervalMinutes);
        if (interval == null) throw new ApiException("بازهٔ زمانی پشتیبانی نمی‌شود");
        String url = BASE + "/v5/market/kline?category=spot&symbol=" + ticker.apiSymbol
                + "&interval=" + interval + "&limit=" + Math.max(60, Math.min(1000, limit + 2));
        Object raw = HttpJson.get(url);
        if (!(raw instanceof JSONObject)) throw new ApiException("پاسخ کندل بای‌بیت نامعتبر است");
        JSONObject root = (JSONObject) raw;
        if (root.optInt("retCode", -1) != 0) throw new ApiException("کندل‌های بای‌بیت دریافت نشد");
        JSONObject resultObject = root.optJSONObject("result");
        JSONArray rows = resultObject == null ? null : resultObject.optJSONArray("list");
        if (rows == null) throw new ApiException("کندل‌های بای‌بیت خالی است");
        long width = intervalMinutes * 60_000L;
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 7) continue;
            long openTime = parseLong(row.optString(0));
            candles.add(new Candle(openTime, openTime + width - 1,
                    CandleNormalizer.number(row.optString(1)),
                    CandleNormalizer.number(row.optString(2)),
                    CandleNormalizer.number(row.optString(3)),
                    CandleNormalizer.number(row.optString(4)),
                    CandleNormalizer.number(row.optString(5)),
                    CandleNormalizer.number(row.optString(6))));
        }
        return CandleNormalizer.completed(candles, limit);
    }

    static String intervalToken(int minutes) {
        return minutes == 5 ? "5" : minutes == 15 ? "15"
                : minutes == 60 ? "60" : minutes == 240 ? "240" : null;
    }

    @Override public OrderBookMetrics fetchOrderBook(MarketTicker ticker) throws ApiException {
        Object raw = HttpJson.get(BASE + "/v5/market/orderbook?category=spot&symbol="
                + ticker.apiSymbol + "&limit=50");
        if (!(raw instanceof JSONObject)) throw new ApiException("دفتر سفارش بای‌بیت نامعتبر است");
        JSONObject root = (JSONObject) raw;
        if (root.optInt("retCode", -1) != 0) throw new ApiException("دفتر سفارش بای‌بیت دریافت نشد");
        JSONObject data = root.optJSONObject("result");
        OrderBookMetrics metrics = data == null ? OrderBookMetrics.unavailable()
                : OrderBookParser.parse(data.optJSONArray("b"), data.optJSONArray("a"),
                        ticker.lastPrice);
        if (!metrics.available) throw new ApiException("عمق بازار بای‌بیت کافی نیست");
        return metrics;
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); }
        catch (RuntimeException ignored) { return 0; }
    }
}
