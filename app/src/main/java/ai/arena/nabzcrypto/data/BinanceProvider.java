package ai.arena.nabzcrypto.data;

import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class BinanceProvider implements MarketProvider {
    private static final String[] BASES = {
            "https://api.binance.com",
            "https://api1.binance.com",
            "https://api2.binance.com",
            "https://api3.binance.com",
            "https://data-api.binance.vision"
    };
    private volatile String activeBase = BASES[0];

    @Override public String name() {
        return "Binance Spot";
    }

    @Override public List<MarketTicker> fetchTickers() throws ApiException {
        ApiException last = null;
        for (String base : orderedBases()) {
            try {
                Object raw = HttpJson.get(base + "/api/v3/ticker/24hr");
                if (!(raw instanceof JSONArray)) throw new ApiException("پاسخ تیکر بایننس نامعتبر است");
                List<MarketTicker> parsed = parseTickers((JSONArray) raw);
                if (parsed.size() < 10) throw new ApiException("تعداد بازارهای دریافت‌شده کافی نیست");
                activeBase = base;
                return parsed;
            } catch (ApiException e) {
                last = e;
            }
        }
        throw last == null ? new ApiException("بایننس در دسترس نیست") : last;
    }

    @Override public List<Candle> fetchCandles(MarketTicker ticker, int intervalMinutes, int limit)
            throws ApiException {
        String interval = intervalMinutes == 5 ? "5m" : intervalMinutes == 15 ? "15m" : null;
        if (interval == null) throw new ApiException("بازهٔ زمانی پشتیبانی نمی‌شود");
        String path = "/api/v3/klines?symbol=" + ticker.apiSymbol + "&interval=" + interval
                + "&limit=" + Math.max(60, Math.min(200, limit + 2));
        ApiException first;
        try {
            return parseCandles(HttpJson.get(activeBase + path), limit);
        } catch (ApiException e) {
            first = e;
        }
        for (String base : BASES) {
            if (base.equals(activeBase)) continue;
            try {
                List<Candle> result = parseCandles(HttpJson.get(base + path), limit);
                activeBase = base;
                return result;
            } catch (ApiException ignored) {
                // Try the next public host.
            }
        }
        throw first;
    }

    private List<String> orderedBases() {
        List<String> result = new ArrayList<>();
        result.add(activeBase);
        for (String base : BASES) if (!base.equals(activeBase)) result.add(base);
        return result;
    }

    private static List<MarketTicker> parseTickers(JSONArray array) {
        long now = System.currentTimeMillis();
        List<MarketTicker> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String symbol = item.optString("symbol", "").toUpperCase();
            if (!symbol.endsWith("USDT") || symbol.length() <= 4) continue;
            String base = symbol.substring(0, symbol.length() - 4);
            MarketTicker ticker = new MarketTicker(
                    symbol, symbol, base, "USDT",
                    CandleNormalizer.number(item.optString("lastPrice")),
                    CandleNormalizer.number(item.optString("priceChangePercent")),
                    CandleNormalizer.number(item.optString("quoteVolume")), now);
            if (ticker.isValid()) result.add(ticker);
        }
        return result;
    }

    private static List<Candle> parseCandles(Object raw, int limit) throws ApiException {
        if (!(raw instanceof JSONArray)) throw new ApiException("پاسخ کندل بایننس نامعتبر است");
        JSONArray rows = (JSONArray) raw;
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 8) continue;
            candles.add(new Candle(
                    row.optLong(0), row.optLong(6),
                    CandleNormalizer.number(row.optString(1)),
                    CandleNormalizer.number(row.optString(2)),
                    CandleNormalizer.number(row.optString(3)),
                    CandleNormalizer.number(row.optString(4)),
                    CandleNormalizer.number(row.optString(5)),
                    CandleNormalizer.number(row.optString(7))));
        }
        return CandleNormalizer.completed(candles, limit);
    }
}
