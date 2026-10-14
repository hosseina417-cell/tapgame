package ai.arena.nabzcrypto.data;

import android.content.Context;
import android.content.SharedPreferences;

import ai.arena.nabzcrypto.model.AiAssessment;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A small local cache so the UI is useful before the first network response. */
public final class ResultCache {
    private static final String FILE = "nabz_scan_cache_v5";
    private static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    private final SharedPreferences preferences;

    public ResultCache(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public long savedAt() {
        return preferences.getLong("saved_at", 0L);
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    public void save(List<ScanResult> results) {
        try {
            JSONArray rows = new JSONArray();
            int count = Math.min(140, results.size());
            for (int i = 0; i < count; i++) rows.put(encode(results.get(i)));
            JSONObject root = new JSONObject();
            root.put("version", 3);
            root.put("savedAt", System.currentTimeMillis());
            root.put("results", rows);
            preferences.edit()
                    .putLong("saved_at", root.optLong("savedAt"))
                    .putString("payload", root.toString())
                    .apply();
        } catch (Exception ignored) {
            // Cache failure must never break a live scan.
        }
    }

    public List<ScanResult> load() {
        long saved = savedAt();
        if (saved <= 0 || System.currentTimeMillis() - saved > MAX_AGE_MS) return Collections.emptyList();
        String payload = preferences.getString("payload", "");
        if (payload.isEmpty()) return Collections.emptyList();
        List<ScanResult> results = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(payload);
            int version = root.optInt("version");
            if (version < 1 || version > 3) return Collections.emptyList();
            JSONArray rows = root.optJSONArray("results");
            if (rows == null) return Collections.emptyList();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                ScanResult result = decode(row);
                if (result != null) results.add(result);
            }
            MarketRepository.sortResults(results);
            return results;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static JSONObject encode(ScanResult result) {
        JSONObject row = new JSONObject();
        try {
            row.put("symbol", result.ticker.symbol);
            row.put("apiSymbol", result.ticker.apiSymbol);
            row.put("base", result.ticker.baseAsset);
            row.put("price", result.ticker.lastPrice);
            row.put("change24", result.ticker.change24hPct);
            row.put("volume24", result.ticker.quoteVolume24h);
            row.put("received", result.ticker.receivedAt);
            row.put("sourceCount", result.ticker.sourceCount);
            row.put("spread", result.ticker.priceSpreadPct);
            row.put("marketCapRank", result.ticker.marketCapRank);
            row.put("marketCap", result.ticker.marketCapUsd);
            row.put("signal", result.signal.name());
            row.put("score", result.score);
            row.put("strength", result.evidenceStrength);
            row.put("aiTrained", result.ai.trained);
            row.put("aiReliable", result.ai.reliable);
            row.put("aiUp", result.ai.probabilityUp);
            row.put("aiConfidence", result.ai.confidence);
            row.put("aiAccuracy", result.ai.validationAccuracy);
            row.put("aiBrier", result.ai.validationBrier);
            row.put("aiTrain", result.ai.trainingSamples);
            row.put("aiValidation", result.ai.validationSamples);
            row.put("aiVote", result.ai.vote);
            row.put("rsi", result.rsi14);
            row.put("ema9", result.ema9);
            row.put("ema21", result.ema21);
            row.put("ema50", result.ema50);
            row.put("macd", result.macdHistogram);
            row.put("atr", result.atr14);
            row.put("ret5", result.return5mPct);
            row.put("ret15", result.return15mPct);
            row.put("ret1h", result.return1hPct);
            row.put("volumeRatio", result.volumeRatio);
            row.put("z", result.movementZScore);
            row.put("reference", result.referencePrice);
            row.put("zoneLow", result.zoneLow);
            row.put("zoneHigh", result.zoneHigh);
            row.put("invalidation", result.invalidation);
            row.put("objective1", result.objective1);
            row.put("objective2", result.objective2);
            row.put("closedAt", result.candleClosedAt);
            row.put("source", result.source);
            row.put("reasons", strings(result.reasons));
            row.put("cautions", strings(result.cautions));
            JSONArray spark = new JSONArray();
            for (double point : result.sparkline) spark.put(point);
            row.put("spark", spark);
        } catch (Exception ignored) {
            // org.json only fails for non-finite values; analysis guards against those.
        }
        return row;
    }

    private static ScanResult decode(JSONObject row) {
        if (row == null) return null;
        try {
            String symbol = row.getString("symbol");
            MarketTicker ticker = new MarketTicker(symbol, row.optString("apiSymbol", symbol),
                    row.getString("base"), "USDT", row.getDouble("price"),
                    row.getDouble("change24"), row.getDouble("volume24"),
                    row.optLong("received", 0), row.optInt("sourceCount", 1),
                    row.optDouble("spread", 0), row.optInt("marketCapRank", 0),
                    row.optDouble("marketCap", 0));
            if (!ticker.isValid()) return null;
            Signal signal = Signal.valueOf(row.getString("signal"));
            AiAssessment ai = new AiAssessment(row.optBoolean("aiTrained", false),
                    row.optBoolean("aiReliable", false), row.optDouble("aiUp", 0.5),
                    row.optInt("aiConfidence", 0), row.optDouble("aiAccuracy", 0),
                    row.optDouble("aiBrier", 1), row.optInt("aiTrain", 0),
                    row.optInt("aiValidation", 0), row.optInt("aiVote", 0));
            JSONArray sparkJson = row.optJSONArray("spark");
            double[] spark = new double[sparkJson == null ? 0 : sparkJson.length()];
            for (int i = 0; i < spark.length; i++) spark[i] = sparkJson.optDouble(i);
            return new ScanResult(ticker, signal, row.getInt("score"),
                    row.getInt("strength"), ai, row.getDouble("rsi"), row.getDouble("ema9"),
                    row.getDouble("ema21"), row.getDouble("ema50"), row.getDouble("macd"),
                    row.getDouble("atr"), row.getDouble("ret5"), row.getDouble("ret15"),
                    row.getDouble("ret1h"), row.getDouble("volumeRatio"), row.getDouble("z"),
                    row.getDouble("reference"), row.getDouble("zoneLow"),
                    row.getDouble("zoneHigh"), row.getDouble("invalidation"),
                    row.getDouble("objective1"), row.getDouble("objective2"),
                    row.getLong("closedAt"), row.optString("source"), readStrings(row.optJSONArray("reasons")),
                    readStrings(row.optJSONArray("cautions")), spark, true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONArray strings(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static List<String> readStrings(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) result.add(value);
            }
        }
        return result;
    }
}
