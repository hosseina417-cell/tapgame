package ai.arena.nabzcrypto.data;

import android.content.Context;
import android.content.SharedPreferences;

import ai.arena.nabzcrypto.model.AiAssessment;
import ai.arena.nabzcrypto.model.MarketRegime;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.OrderBookMetrics;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;
import ai.arena.nabzcrypto.model.SignalDiagnostics;
import ai.arena.nabzcrypto.model.TradeChartData;

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
            root.put("version", 5);
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
            if (version != 5) return Collections.emptyList();
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
            row.put("technicalScore", result.technicalScore);
            row.put("strength", result.evidenceStrength);
            row.put("dataQuality", result.dataQuality);
            row.put("regime", result.regime.name());
            row.put("adx", result.adx14);
            row.put("aiTrained", result.ai.trained);
            row.put("aiReliable", result.ai.reliable);
            row.put("aiUp", result.ai.probabilityUp);
            row.put("aiDown", result.ai.probabilityDown);
            row.put("aiNeutral", result.ai.probabilityNeutral);
            row.put("aiConfidence", result.ai.confidence);
            row.put("aiAccuracy", result.ai.validationAccuracy);
            row.put("aiBrier", result.ai.validationBrier);
            row.put("aiTrain", result.ai.trainingSamples);
            row.put("aiValidation", result.ai.validationSamples);
            row.put("aiVote", result.ai.vote);
            row.put("aiAgreement", result.ai.horizonAgreement);
            row.put("aiModels", result.ai.modelsUsed);
            row.put("aiFolds", result.ai.validationFolds);
            row.put("aiDrift", result.ai.driftDetected);
            row.put("aiRecentAccuracy", result.ai.recentValidationAccuracy);
            row.put("trend1h", result.diagnostics.oneHourTrend);
            row.put("trend4h", result.diagnostics.fourHourTrend);
            row.put("benchmarkTrend", result.diagnostics.benchmarkTrend);
            row.put("higherAligned", result.diagnostics.higherTimeframeAligned);
            row.put("bookAvailable", result.diagnostics.orderBook.available);
            row.put("bookSpread", result.diagnostics.orderBook.spreadPct);
            row.put("bookBidDepth", result.diagnostics.orderBook.bidDepthUsd);
            row.put("bookAskDepth", result.diagnostics.orderBook.askDepthUsd);
            row.put("bookImbalance", result.diagnostics.orderBook.imbalance);
            row.put("bookSlippage", result.diagnostics.orderBook.estimatedSlippagePct);
            row.put("rr1", result.diagnostics.riskReward1);
            row.put("rr2", result.diagnostics.riskReward2);
            row.put("roundTripCost", result.diagnostics.estimatedRoundTripCostPct);
            row.put("position1000", result.diagnostics.positionSizePer1000);
            row.put("rrPassed", result.diagnostics.riskRewardPassed);
            row.put("liquidityPassed", result.diagnostics.liquidityPassed);
            row.put("historyDays", result.diagnostics.historyDays);
            row.put("qualityStrong", result.diagnostics.qualityStrong);
            row.put("technicalStrong", result.diagnostics.technicalStrong);
            row.put("regimeStrong", result.diagnostics.regimeStrong);
            row.put("independentConfirmation", result.diagnostics.independentConfirmation);
            row.put("strongEligible", result.diagnostics.strongEligible);
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
            JSONArray chart = new JSONArray();
            int chartStart = Math.max(0, result.chartData.size() - 96);
            for (int i = chartStart; i < result.chartData.size(); i++) {
                JSONArray candle = new JSONArray();
                candle.put(result.chartData.openTimes[i]);
                candle.put(result.chartData.opens[i]);
                candle.put(result.chartData.highs[i]);
                candle.put(result.chartData.lows[i]);
                candle.put(result.chartData.closes[i]);
                chart.put(candle);
            }
            row.put("chart", chart);
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
            double aiUp = row.optDouble("aiUp", 0.5);
            double aiDown = row.has("aiDown") ? row.optDouble("aiDown", 0)
                    : Math.max(0, 1.0 - aiUp);
            double aiNeutral = row.optDouble("aiNeutral", 0);
            AiAssessment ai = new AiAssessment(row.optBoolean("aiTrained", false),
                    row.optBoolean("aiReliable", false), aiUp, aiDown, aiNeutral,
                    row.optInt("aiConfidence", 0), row.optDouble("aiAccuracy", 0),
                    row.optDouble("aiBrier", 1), row.optInt("aiTrain", 0),
                    row.optInt("aiValidation", 0), row.optInt("aiVote", 0),
                    row.optBoolean("aiAgreement", false), row.optInt("aiModels", 1),
                    row.optInt("aiFolds", 1), row.optBoolean("aiDrift", false),
                    row.optDouble("aiRecentAccuracy", 0));
            JSONArray sparkJson = row.optJSONArray("spark");
            double[] spark = new double[sparkJson == null ? 0 : sparkJson.length()];
            for (int i = 0; i < spark.length; i++) spark[i] = sparkJson.optDouble(i);
            OrderBookMetrics book = new OrderBookMetrics(
                    row.optBoolean("bookAvailable", false), row.optDouble("bookSpread", 0),
                    row.optDouble("bookBidDepth", 0), row.optDouble("bookAskDepth", 0),
                    row.optDouble("bookImbalance", 0), row.optDouble("bookSlippage", 0));
            SignalDiagnostics diagnostics = new SignalDiagnostics(
                    row.optInt("trend1h", 0), row.optInt("trend4h", 0),
                    row.optInt("benchmarkTrend", 0), row.optBoolean("higherAligned", false),
                    book, row.optDouble("rr1", 0), row.optDouble("rr2", 0),
                    row.optDouble("roundTripCost", 0.20), row.optDouble("position1000", 0),
                    row.optBoolean("rrPassed", false), row.optBoolean("liquidityPassed", true),
                    row.optInt("historyDays", 0), row.optBoolean("qualityStrong", false),
                    row.optBoolean("technicalStrong", false), row.optBoolean("regimeStrong", false),
                    row.optBoolean("independentConfirmation", false),
                    row.optBoolean("strongEligible", false));
            TradeChartData chartData = readChart(row.optJSONArray("chart"));
            int combinedScore = row.getInt("score");
            return new ScanResult(ticker, signal, combinedScore,
                    row.optInt("technicalScore", combinedScore), row.getInt("strength"),
                    row.optInt("dataQuality", 80), ai,
                    parseRegime(row.optString("regime", "TRANSITION")),
                    row.optDouble("adx", 0), row.getDouble("rsi"), row.getDouble("ema9"),
                    row.getDouble("ema21"), row.getDouble("ema50"), row.getDouble("macd"),
                    row.getDouble("atr"), row.getDouble("ret5"), row.getDouble("ret15"),
                    row.getDouble("ret1h"), row.getDouble("volumeRatio"), row.getDouble("z"),
                    row.getDouble("reference"), row.getDouble("zoneLow"),
                    row.getDouble("zoneHigh"), row.getDouble("invalidation"),
                    row.getDouble("objective1"), row.getDouble("objective2"),
                    row.getLong("closedAt"), row.optString("source"), readStrings(row.optJSONArray("reasons")),
                    readStrings(row.optJSONArray("cautions")), spark, true,
                    diagnostics, chartData);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static TradeChartData readChart(JSONArray array) {
        if (array == null || array.length() == 0) return TradeChartData.empty();
        int count = array.length();
        long[] times = new long[count];
        double[] opens = new double[count];
        double[] highs = new double[count];
        double[] lows = new double[count];
        double[] closes = new double[count];
        for (int i = 0; i < count; i++) {
            JSONArray candle = array.optJSONArray(i);
            if (candle == null || candle.length() < 5) return TradeChartData.empty();
            times[i] = candle.optLong(0);
            opens[i] = candle.optDouble(1);
            highs[i] = candle.optDouble(2);
            lows[i] = candle.optDouble(3);
            closes[i] = candle.optDouble(4);
        }
        return new TradeChartData(times, opens, highs, lows, closes);
    }

    private static MarketRegime parseRegime(String value) {
        try {
            return MarketRegime.valueOf(value);
        } catch (RuntimeException ignored) {
            return MarketRegime.TRANSITION;
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
