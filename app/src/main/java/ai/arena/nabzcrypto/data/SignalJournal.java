package ai.arena.nabzcrypto.data;

import android.content.Context;
import android.content.SharedPreferences;

import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketRegime;
import ai.arena.nabzcrypto.model.PerformanceStats;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.SignalEvent;
import ai.arena.nabzcrypto.model.SignalRecord;
import ai.arena.nabzcrypto.model.SignalState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local paper journal with conservative candle-order assumptions and cost-adjusted outcomes. */
public final class SignalJournal {
    private static final String FILE = "signal_journal_v1";
    private static final String KEY = "records";
    private static final String KEY_OBSERVED = "observed_directions";
    private static final long EXPIRY_MS = 12L * 60L * 60L * 1000L;
    private static final long PENDING_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long REENTRY_COOLDOWN_MS = 3L * 60L * 60L * 1000L;
    private static final int MAX_RECORDS = 500;

    private final SharedPreferences preferences;
    private final List<SignalRecord> records = new ArrayList<>();
    private final List<SignalEvent> events = new ArrayList<>();
    private final Map<String, Integer> observedDirections = new HashMap<>();
    private boolean dirty;

    public SignalJournal(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        load();
    }

    /** In-memory journal for deterministic JVM tests; production always uses the Context form. */
    SignalJournal() {
        preferences = null;
    }

    public synchronized void observe(ScanResult result, List<Candle> candles) {
        if (result == null || result.ticker == null) return;
        String symbol = result.ticker.symbol;
        int direction = signalDirection(result);
        int previousDirection = observedDirections.containsKey(symbol)
                ? observedDirections.get(symbol) : 0;
        if (previousDirection != direction) {
            observedDirections.put(symbol, direction);
            dirty = true;
        }

        SignalRecord active = activeFor(symbol);
        boolean oppositeTransition = direction != 0 && previousDirection == -direction;
        if (active != null) {
            evaluate(active, candles, result.referencePrice, result.candleClosedAt);
            if (active.active()) {
                if (direction == active.direction
                        && result.candleClosedAt > active.issuedAt
                        && active.state == SignalState.PENDING) {
                    transition(active, SignalState.CONFIRMED, result.candleClosedAt,
                            "سیگنال " + active.baseAsset + " با اسکن بعدی تأیید شد");
                } else if (direction == -active.direction) {
                    oppositeTransition = true;
                    boolean afterTarget1 = active.state == SignalState.TARGET_1_HIT;
                    finish(active, afterTarget1 ? SignalState.TP1_THEN_INVALIDATED
                                    : SignalState.INVALIDATED,
                            afterTarget1
                                    ? (active.target1 + result.referencePrice) / 2.0
                                    : result.referencePrice,
                            result.candleClosedAt,
                            "جهت سیگنال " + active.baseAsset + " تغییر کرد و تحلیل قبلی باطل شد");
                } else if (active.state == SignalState.PENDING
                        && result.candleClosedAt - active.issuedAt >= PENDING_TIMEOUT_MS) {
                    finish(active, SignalState.EXPIRED, result.referencePrice,
                            result.candleClosedAt,
                            "سیگنال " + active.baseAsset + " بدون تأیید منقضی شد");
                }
            }
        }

        if (direction == 0 || activeFor(symbol) != null) return;
        // A direction must disappear or flip before it can be issued again. This survives
        // restarts through observedDirections and prevents repeated alerts on every scan.
        if (!oppositeTransition && previousDirection == direction) return;
        SignalRecord latest = latestFor(symbol);
        if (!oppositeTransition && latest != null && latest.state.terminal
                && result.candleClosedAt - latest.closedAt < REENTRY_COOLDOWN_MS) return;
        create(result, direction);
    }

    private void create(ScanResult result, int direction) {
        boolean strong = result.signal.name().startsWith("STRONG_");
        // Paper outcomes are anchored to the exact market price at issuance, not to an
        // idealized fill somewhere inside the displayed entry zone.
        double entry = result.referencePrice;
        SignalState initial = strong ? SignalState.CONFIRMED : SignalState.PENDING;
        String id = result.ticker.symbol + "-" + result.candleClosedAt;
        double slippage = result.diagnostics.orderBook.available
                ? Math.min(2.0, result.diagnostics.orderBook.estimatedSlippagePct * 2.0)
                : 0.10;
        double fee = Math.max(0,
                result.diagnostics.estimatedRoundTripCostPct - slippage);
        SignalRecord created = new SignalRecord(id, result.ticker.symbol,
                result.ticker.baseAsset, direction, strong, result.candleClosedAt,
                result.candleClosedAt, 0, entry, result.invalidation,
                result.objective1, result.objective2, result.regime,
                result.score, result.dataQuality, fee, slippage,
                initial, 0, 0, 0, 0);
        records.add(0, created);
        trim();
        dirty = true;
        events.add(new SignalEvent(created.id, created.symbol, created.state,
                (strong ? "سیگنال قوی جدید " : "سیگنال جدید در انتظار تأیید ")
                        + created.baseAsset + " • " + created.directionFa(),
                created.issuedAt));
    }

    public synchronized void flush() {
        if (!dirty) return;
        if (preferences == null) {
            dirty = false;
            return;
        }
        JSONArray array = new JSONArray();
        for (SignalRecord record : records) array.put(encode(record));
        JSONObject observed = new JSONObject();
        for (Map.Entry<String, Integer> entry : observedDirections.entrySet()) {
            try {
                observed.put(entry.getKey(), entry.getValue());
            } catch (Exception ignored) {
            }
        }
        preferences.edit().putString(KEY, array.toString())
                .putString(KEY_OBSERVED, observed.toString()).apply();
        dirty = false;
    }

    public synchronized SignalRecord latestForSymbol(String symbol) {
        if (symbol == null) return null;
        for (SignalRecord record : records) {
            if (symbol.equals(record.symbol)) return record.copy();
        }
        return null;
    }

    public synchronized java.util.Set<String> activeSymbols() {
        java.util.Set<String> symbols = new java.util.HashSet<>();
        for (SignalRecord record : records) if (record.active()) symbols.add(record.symbol);
        return symbols;
    }

    public synchronized List<SignalRecord> records() {
        List<SignalRecord> snapshot = new ArrayList<>();
        for (SignalRecord record : records) snapshot.add(record.copy());
        return Collections.unmodifiableList(snapshot);
    }

    public synchronized List<SignalEvent> drainEvents() {
        List<SignalEvent> result = new ArrayList<>(events);
        events.clear();
        return result;
    }

    public synchronized PerformanceStats stats() {
        return calculate(records);
    }

    public synchronized Map<Integer, PerformanceStats> directionStats() {
        List<SignalRecord> buys = new ArrayList<>();
        List<SignalRecord> sells = new ArrayList<>();
        for (SignalRecord record : records) {
            (record.direction > 0 ? buys : sells).add(record);
        }
        Map<Integer, PerformanceStats> result = new LinkedHashMap<>();
        result.put(1, calculate(buys));
        result.put(-1, calculate(sells));
        return result;
    }

    public synchronized Map<String, PerformanceStats> symbolStats() {
        Map<String, List<SignalRecord>> grouped = new LinkedHashMap<>();
        for (SignalRecord record : records) {
            grouped.computeIfAbsent(record.symbol, ignored -> new ArrayList<>()).add(record);
        }
        Map<String, PerformanceStats> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<SignalRecord>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), calculate(entry.getValue()));
        }
        return result;
    }

    public synchronized Map<MarketRegime, PerformanceStats> regimeStats() {
        Map<MarketRegime, List<SignalRecord>> grouped = new EnumMap<>(MarketRegime.class);
        for (MarketRegime regime : MarketRegime.values()) grouped.put(regime, new ArrayList<>());
        for (SignalRecord record : records) grouped.get(record.regime).add(record);
        Map<MarketRegime, PerformanceStats> result = new EnumMap<>(MarketRegime.class);
        for (Map.Entry<MarketRegime, List<SignalRecord>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), calculate(entry.getValue()));
        }
        return result;
    }

    public synchronized void clear() {
        records.clear();
        events.clear();
        observedDirections.clear();
        dirty = false;
        if (preferences != null) preferences.edit().clear().apply();
    }

    private void evaluate(SignalRecord record, List<Candle> candles,
                          double currentPrice, long evaluatedAt) {
        if (!record.active()) return;
        List<Candle> ordered = new ArrayList<>();
        if (candles != null) {
            for (Candle candle : candles) {
                if (candle != null && candle.isValid()
                        && candle.closeTime > record.lastEvaluatedAt) ordered.add(candle);
            }
        }
        ordered.sort(Comparator.comparingLong(c -> c.openTime));
        for (Candle candle : ordered) {
            if (!record.active()) break;
            boolean stop = record.direction > 0
                    ? candle.low <= record.invalidation : candle.high >= record.invalidation;
            boolean second = record.direction > 0
                    ? candle.high >= record.target2 : candle.low <= record.target2;
            boolean first = record.direction > 0
                    ? candle.high >= record.target1 : candle.low <= record.target1;
            // OHLC does not reveal intrabar order; assuming stop first avoids optimistic backtests.
            if (stop) {
                if (record.state == SignalState.TARGET_1_HIT) {
                    finish(record, SignalState.TP1_THEN_INVALIDATED,
                            (record.target1 + record.invalidation) / 2.0, candle.closeTime,
                            "پس از هدف ۱، سطح ابطال " + record.baseAsset + " لمس شد");
                } else {
                    finish(record, SignalState.INVALIDATED, record.invalidation, candle.closeTime,
                            "سطح ابطال " + record.baseAsset + " لمس شد");
                }
                break;
            }
            if (second) {
                finish(record, SignalState.TARGET_2_HIT,
                        (record.target1 + record.target2) / 2.0, candle.closeTime,
                        "هدف ۲ سیگنال " + record.baseAsset + " لمس شد");
                break;
            }
            if (first && record.state != SignalState.TARGET_1_HIT) {
                record.target1HitAt = candle.closeTime;
                transition(record, SignalState.TARGET_1_HIT, candle.closeTime,
                        "هدف ۱ سیگنال " + record.baseAsset + " لمس شد");
            }
            record.lastEvaluatedAt = Math.max(record.lastEvaluatedAt, candle.closeTime);
            if (candle.closeTime - record.issuedAt >= EXPIRY_MS && record.active()) {
                boolean afterTarget1 = record.state == SignalState.TARGET_1_HIT;
                finish(record, afterTarget1
                                ? SignalState.EXPIRED_AFTER_TP1 : SignalState.EXPIRED,
                        afterTarget1 ? (record.target1 + candle.close) / 2.0 : candle.close,
                        candle.closeTime,
                        "زمان ارزیابی سیگنال " + record.baseAsset + " پایان یافت");
            }
        }
        record.lastEvaluatedAt = Math.max(record.lastEvaluatedAt, evaluatedAt);
        if (record.active() && evaluatedAt - record.issuedAt >= EXPIRY_MS) {
            boolean afterTarget1 = record.state == SignalState.TARGET_1_HIT;
            finish(record, afterTarget1
                            ? SignalState.EXPIRED_AFTER_TP1 : SignalState.EXPIRED,
                    afterTarget1 ? (record.target1 + currentPrice) / 2.0 : currentPrice,
                    evaluatedAt,
                    "زمان ارزیابی سیگنال " + record.baseAsset + " پایان یافت");
        }
    }

    private void transition(SignalRecord record, SignalState state, long at, String message) {
        if (record.state == state) return;
        record.state = state;
        if (state == SignalState.CONFIRMED && record.confirmedAt == 0) {
            record.confirmedAt = Math.max(record.issuedAt, at);
        }
        record.lastEvaluatedAt = Math.max(record.lastEvaluatedAt, at);
        dirty = true;
        events.add(new SignalEvent(record.id, record.symbol, state, message, at));
    }

    private void finish(SignalRecord record, SignalState state, double exitPrice,
                        long at, String message) {
        record.state = state;
        record.closedAt = Math.max(record.issuedAt, at);
        record.lastEvaluatedAt = Math.max(record.lastEvaluatedAt, at);
        record.exitPrice = Double.isFinite(exitPrice) && exitPrice > 0 ? exitPrice : record.entryPrice;
        double gross = record.direction * (record.exitPrice / record.entryPrice - 1.0) * 100.0;
        record.netReturnPct = gross - record.estimatedCostPct;
        double riskPct = Math.abs(record.entryPrice - record.invalidation)
                / Math.max(1e-12, record.entryPrice) * 100.0;
        record.outcomeR = riskPct > 0 ? record.netReturnPct / riskPct : 0;
        dirty = true;
        events.add(new SignalEvent(record.id, record.symbol, state, message, at));
    }

    private SignalRecord activeFor(String symbol) {
        for (SignalRecord record : records) {
            if (record.symbol.equals(symbol) && record.active()) return record;
        }
        return null;
    }

    private SignalRecord latestFor(String symbol) {
        for (SignalRecord record : records) if (record.symbol.equals(symbol)) return record;
        return null;
    }

    private static int signalDirection(ScanResult result) {
        return result.signal.isBuy() ? 1 : result.signal.isSell() ? -1 : 0;
    }

    private void trim() {
        while (records.size() > MAX_RECORDS) records.remove(records.size() - 1);
    }

    private static PerformanceStats calculate(List<SignalRecord> source) {
        int active = 0;
        int completed = 0;
        int positive = 0;
        int invalidated = 0;
        double sumR = 0;
        double sumNet = 0;
        for (SignalRecord record : source) {
            if (record.active()) {
                active++;
                continue;
            }
            completed++;
            if (record.outcomeR > 0) positive++;
            if (record.state == SignalState.INVALIDATED
                    || record.state == SignalState.TP1_THEN_INVALIDATED) invalidated++;
            sumR += record.outcomeR;
            sumNet += record.netReturnPct;
        }
        return new PerformanceStats(source.size(), active, completed, positive, invalidated,
                completed > 0 ? positive * 100.0 / completed : 0,
                completed > 0 ? sumR / completed : 0,
                completed > 0 ? sumNet / completed : 0);
    }

    private void load() {
        String payload = preferences.getString(KEY, "");
        if (payload != null && !payload.isEmpty()) {
            try {
                JSONArray array = new JSONArray(payload);
                for (int i = 0; i < array.length() && records.size() < MAX_RECORDS; i++) {
                    SignalRecord record = decode(array.optJSONObject(i));
                    if (record != null) records.add(record);
                }
            } catch (Exception ignored) {
                records.clear();
            }
        }
        String directions = preferences.getString(KEY_OBSERVED, "");
        if (directions == null || directions.isEmpty()) return;
        try {
            JSONObject object = new JSONObject(directions);
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String symbol = keys.next();
                observedDirections.put(symbol, object.optInt(symbol, 0));
            }
        } catch (Exception ignored) {
            observedDirections.clear();
        }
    }

    private static JSONObject encode(SignalRecord record) {
        JSONObject row = new JSONObject();
        try {
            row.put("id", record.id);
            row.put("symbol", record.symbol);
            row.put("base", record.baseAsset);
            row.put("direction", record.direction);
            row.put("strong", record.strong);
            row.put("issued", record.issuedAt);
            row.put("confirmed", record.confirmedAt);
            row.put("evaluated", record.lastEvaluatedAt);
            row.put("closed", record.closedAt);
            row.put("entry", record.entryPrice);
            row.put("stop", record.invalidation);
            row.put("target1", record.target1);
            row.put("target2", record.target2);
            row.put("regime", record.regime.name());
            row.put("score", record.score);
            row.put("quality", record.dataQuality);
            row.put("fee", record.estimatedFeePct);
            row.put("slippage", record.estimatedSlippagePct);
            row.put("cost", record.estimatedCostPct);
            row.put("state", record.state.name());
            row.put("target1At", record.target1HitAt);
            row.put("exit", record.exitPrice);
            row.put("net", record.netReturnPct);
            row.put("r", record.outcomeR);
        } catch (Exception ignored) {
            // All values are bounded and finite before encoding.
        }
        return row;
    }

    private static SignalRecord decode(JSONObject row) {
        if (row == null) return null;
        try {
            double legacyCost = row.optDouble("cost", 0.20);
            double fee = row.has("fee") ? row.optDouble("fee", 0) : legacyCost;
            double slippage = row.has("slippage") ? row.optDouble("slippage", 0) : 0;
            SignalRecord record = new SignalRecord(row.getString("id"), row.getString("symbol"),
                    row.optString("base"), row.optInt("direction", 1),
                    row.optBoolean("strong"), row.optLong("issued"),
                    row.optLong("evaluated"), row.optLong("closed"),
                    row.getDouble("entry"), row.getDouble("stop"),
                    row.getDouble("target1"), row.getDouble("target2"),
                    MarketRegime.valueOf(row.optString("regime", "TRANSITION")),
                    row.optInt("score"), row.optInt("quality"), fee, slippage,
                    SignalState.valueOf(row.optString("state", "EXPIRED")),
                    row.optLong("target1At"), row.optDouble("exit"),
                    row.optDouble("net"), row.optDouble("r"));
            record.confirmedAt = Math.max(0, row.optLong("confirmed", record.confirmedAt));
            return record;
        } catch (Exception ignored) {
            return null;
        }
    }
}
