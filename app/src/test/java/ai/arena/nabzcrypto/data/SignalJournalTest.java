package ai.arena.nabzcrypto.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.arena.nabzcrypto.model.AiAssessment;
import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketRegime;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.OrderBookMetrics;
import ai.arena.nabzcrypto.model.PerformanceStats;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;
import ai.arena.nabzcrypto.model.SignalDiagnostics;
import ai.arena.nabzcrypto.model.SignalRecord;
import ai.arena.nabzcrypto.model.SignalState;
import ai.arena.nabzcrypto.model.TradeChartData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

public final class SignalJournalTest {
    private static final long STEP = 5L * 60L * 1000L;

    @Test public void lifecycleTracksConfirmationTargetsCostsAndNoDuplicates() {
        SignalJournal journal = new SignalJournal();
        long t0 = System.currentTimeMillis() - 60L * 60L * 1000L;
        journal.observe(result("BTCUSDT", Signal.BUY, t0, 100), Collections.emptyList());
        assertEquals(1, journal.records().size());
        assertEquals(SignalState.PENDING, journal.records().get(0).state);
        assertEquals(100, journal.records().get(0).entryPrice, 1e-9);
        assertTrue(journal.activeSymbols().contains("BTCUSDT"));

        journal.observe(result("BTCUSDT", Signal.BUY, t0 + STEP, 100),
                Collections.emptyList());
        SignalRecord confirmed = journal.records().get(0);
        assertEquals(SignalState.CONFIRMED, confirmed.state);
        assertEquals(t0 + STEP, confirmed.confirmedAt);

        journal.observe(result("BTCUSDT", Signal.BUY, t0 + 2 * STEP, 109),
                Collections.singletonList(candle(t0 + STEP, 100, 111, 99, 109)));
        SignalRecord target1 = journal.records().get(0);
        assertEquals(SignalState.TARGET_1_HIT, target1.state);
        assertEquals(t0 + 2 * STEP - 1, target1.target1HitAt);

        journal.observe(result("BTCUSDT", Signal.BUY, t0 + 3 * STEP, 91),
                Collections.singletonList(candle(t0 + 2 * STEP, 109, 109, 89, 91)));
        SignalRecord finished = journal.records().get(0);
        assertEquals(SignalState.TP1_THEN_INVALIDATED, finished.state);
        assertEquals(100, finished.exitPrice, 1e-9); // half at 110, half at 90
        assertEquals(0.20, finished.estimatedFeePct, 1e-9);
        assertEquals(0.20, finished.estimatedSlippagePct, 1e-9);
        assertEquals(-0.40, finished.netReturnPct, 1e-9);
        assertEquals(-0.40 / 10.40, finished.outcomeR, 1e-9);
        assertTrue(!journal.activeSymbols().contains("BTCUSDT"));

        journal.observe(result("BTCUSDT", Signal.BUY, t0 + 4 * STEP, 101),
                Collections.emptyList());
        assertEquals(1, journal.records().size());
        PerformanceStats stats = journal.stats();
        assertEquals(1, stats.completedSignals);
        assertEquals(0, stats.positiveOutcomes);
        assertEquals(1, stats.invalidatedOutcomes);
        Map<Integer, PerformanceStats> directions = journal.directionStats();
        assertEquals(1, directions.get(1).completedSignals);
        assertEquals(1, journal.symbolStats().get("BTCUSDT").completedSignals);
    }

    @Test public void sameCandleStopWinsAndOppositeTransitionCreatesOnce() {
        SignalJournal journal = new SignalJournal();
        long t0 = System.currentTimeMillis() - 50L * 60L * 1000L;
        journal.observe(result("ETHUSDT", Signal.STRONG_BUY, t0, 100),
                Collections.emptyList());
        journal.observe(result("ETHUSDT", Signal.STRONG_BUY, t0 + STEP, 100),
                Collections.emptyList());
        assertEquals(SignalState.CONFIRMED, journal.records().get(0).state);
        journal.observe(result("ETHUSDT", Signal.STRONG_BUY, t0 + 2 * STEP, 105),
                Collections.singletonList(candle(t0 + STEP, 100, 125, 89, 105)));
        assertEquals(SignalState.INVALIDATED, journal.records().get(0).state);
        assertEquals(-1.0, journal.records().get(0).outcomeR, 1e-9);

        journal.observe(result("ETHUSDT", Signal.SELL, t0 + 3 * STEP, 99),
                Collections.emptyList());
        assertEquals(2, journal.records().size());
        assertEquals(-1, journal.records().get(0).direction);
        journal.observe(result("ETHUSDT", Signal.SELL, t0 + 4 * STEP, 99),
                Collections.emptyList());
        assertEquals(2, journal.records().size());
        assertEquals(SignalState.CONFIRMED, journal.records().get(0).state);
    }

    @Test public void unconfirmedCandidateCannotBecomeALoss() {
        SignalJournal journal = new SignalJournal();
        long t0 = System.currentTimeMillis() - 60L * 60L * 1000L;
        journal.observe(result("XRPUSDT", Signal.BUY, t0, 100), Collections.emptyList());
        journal.observe(result("XRPUSDT", Signal.NEUTRAL, t0 + 7 * STEP, 89),
                Collections.singletonList(candle(t0, 100, 101, 88, 89)));
        SignalRecord record = journal.records().get(0);
        assertEquals(SignalState.EXPIRED, record.state);
        assertTrue(!record.entered());
        assertEquals(0.0, record.netReturnPct, 1e-9);
        assertEquals(0, journal.stats().completedSignals);
    }

    @Test public void confirmationRejectsChasingAndFailedTimingGate() {
        SignalJournal journal = new SignalJournal();
        long t0 = System.currentTimeMillis() - 40L * 60L * 1000L;
        journal.observe(result("ADAUSDT", Signal.BUY, t0, 100), Collections.emptyList());

        // Initial risk is 10; a regular candidate allows at most 35% drift.
        journal.observe(result("ADAUSDT", Signal.BUY, t0 + STEP, 104),
                Collections.emptyList());
        assertEquals(SignalState.PENDING, journal.records().get(0).state);

        journal.observe(resultWithTiming("ADAUSDT", Signal.BUY, t0 + 2 * STEP, 100, false),
                Collections.emptyList());
        assertEquals(SignalState.PENDING, journal.records().get(0).state);

        journal.observe(resultWithCosts("ADAUSDT", Signal.BUY, t0 + 3 * STEP, 100,
                        0.80, 0.20), Collections.emptyList());
        SignalRecord entered = journal.records().get(0);
        assertEquals(SignalState.CONFIRMED, entered.state);
        assertEquals(100, entered.entryPrice, 1e-9);
        assertEquals(0.80, entered.estimatedCostPct, 1e-9);
        assertTrue(entered.entered());
    }

    @Test public void scanBudgetFlagStillEvaluatesButDoesNotReplace() {
        SignalJournal journal = new SignalJournal();
        long t0 = System.currentTimeMillis() - 40L * 60L * 1000L;
        assertTrue(journal.observe(result("AUSDT", Signal.BUY, t0, 100),
                Collections.emptyList(), true));
        assertFalse(journal.observe(result("BUSDT", Signal.BUY, t0, 100),
                Collections.emptyList(), false));
        assertEquals(1, journal.records().size());

        // Lifecycle updates are not blocked by the new-candidate budget.
        journal.observe(result("AUSDT", Signal.BUY, t0 + STEP, 100),
                Collections.emptyList(), false);
        assertEquals(SignalState.CONFIRMED, journal.records().get(0).state);

        // The opposite transition closes the old entry, but cannot arm a replacement.
        assertFalse(journal.observe(result("AUSDT", Signal.SELL, t0 + 2 * STEP, 99),
                Collections.emptyList(), false));
        assertEquals(1, journal.records().size());
        assertEquals(SignalState.INVALIDATED, journal.records().get(0).state);

        // Because the blocked replacement was never armed, it may compete for the next
        // scan's budget without waiting for a neutral tick or same-direction cooldown.
        assertTrue(journal.observe(result("AUSDT", Signal.SELL, t0 + 3 * STEP, 99),
                Collections.emptyList(), true));
        assertEquals(2, journal.records().size());
        assertEquals(SignalState.PENDING, journal.records().get(0).state);
        assertEquals(-1, journal.records().get(0).direction);
    }

    @Test public void losingDirectionActivatesCircuitBreaker() {
        SignalJournal journal = new SignalJournal();
        long t0 = System.currentTimeMillis() - 3L * 60L * 60L * 1000L;
        for (int i = 0; i < 12; i++) {
            String symbol = "T" + i + "USDT";
            long issued = t0 + i * 1000L;
            journal.observe(result(symbol, Signal.STRONG_BUY, issued, 100),
                    Collections.emptyList());
            journal.observe(result(symbol, Signal.STRONG_BUY, issued + STEP, 100),
                    Collections.emptyList());
            journal.observe(result(symbol, Signal.STRONG_BUY, issued + 2 * STEP, 90),
                    Collections.singletonList(candle(issued + STEP, 100, 101, 89, 90)));
        }
        long now = t0 + 20 * STEP;
        assertTrue(journal.directionPaused(1, now));
        boolean created = journal.observe(result("PAUSEUSDT", Signal.BUY, now, 100),
                Collections.emptyList());
        assertTrue(!created);
        assertEquals(12, journal.records().size());
    }

    @Test public void targetTwoUsesHalfAtEachTarget() {
        SignalJournal journal = new SignalJournal();
        long t0 = System.currentTimeMillis() - 40L * 60L * 1000L;
        journal.observe(result("SOLUSDT", Signal.STRONG_BUY, t0, 100),
                Collections.emptyList());
        journal.observe(result("SOLUSDT", Signal.STRONG_BUY, t0 + STEP, 100),
                Collections.emptyList());
        journal.observe(result("SOLUSDT", Signal.STRONG_BUY, t0 + 2 * STEP, 120),
                Collections.singletonList(candle(t0 + STEP, 100, 121, 99, 120)));
        SignalRecord record = journal.records().get(0);
        assertEquals(SignalState.TARGET_2_HIT, record.state);
        assertEquals(115, record.exitPrice, 1e-9);
        assertEquals(14.60, record.netReturnPct, 1e-9);
    }

    private static ScanResult result(String symbol, Signal signal, long time, double price) {
        return resultWithTiming(symbol, signal, time, price, true);
    }

    private static ScanResult resultWithTiming(String symbol, Signal signal, long time,
                                                double price, boolean timingPassed) {
        return resultWithDiagnostics(symbol, signal, time, price, timingPassed, 0.40, 0.10);
    }

    private static ScanResult resultWithCosts(String symbol, Signal signal, long time,
                                               double price, double roundTripCost,
                                               double oneWaySlippage) {
        return resultWithDiagnostics(symbol, signal, time, price, true,
                roundTripCost, oneWaySlippage);
    }

    private static ScanResult resultWithDiagnostics(String symbol, Signal signal, long time,
                                                      double price, boolean timingPassed,
                                                      double roundTripCost,
                                                      double oneWaySlippage) {
        String base = symbol.substring(0, symbol.length() - 4);
        MarketTicker ticker = new MarketTicker(symbol, symbol, base, "USDT", price,
                1, 50_000_000, time, 3, 0.03, 1, 1_000_000_000);
        OrderBookMetrics book = new OrderBookMetrics(true, 0.03,
                100_000, 100_000, 0, oneWaySlippage);
        SignalDiagnostics diagnostics = new SignalDiagnostics(50, 50, 30, true,
                book, 1.5, 2.5, roundTripCost, 100, true, true, 7,
                true, true, true, true, timingPassed, timingPassed);
        return new ScanResult(ticker, signal, signal.isBuy() ? 70 : -70,
                signal.isBuy() ? 60 : -60, 80, 90, AiAssessment.unavailable(),
                signal.isBuy() ? MarketRegime.TREND_UP : MarketRegime.TREND_DOWN,
                30, 55, price, price, price, 0, 10,
                0, 0, 0, 1, 0, price, price, price,
                signal.isBuy() ? 90 : 110, signal.isBuy() ? 110 : 90,
                signal.isBuy() ? 120 : 80, time, "test",
                new ArrayList<>(), new ArrayList<>(), new double[]{price}, false,
                diagnostics, TradeChartData.empty());
    }

    private static Candle candle(long openTime, double open, double high,
                                 double low, double close) {
        return new Candle(openTime, openTime + STEP - 1, open, high, low, close,
                1_000, close * 1_000);
    }
}
