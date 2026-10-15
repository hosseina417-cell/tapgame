package ai.arena.nabzcrypto.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketRegime;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.OrderBookMetrics;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class IndicatorEngineTest {
    @Test public void emaOfConstantSeriesIsConstant() {
        double[] input = new double[80];
        for (int i = 0; i < input.length; i++) input[i] = 42.5;
        double[] result = IndicatorEngine.emaSeries(input, 21);
        for (double value : result) assertEquals(42.5, value, 1e-10);
    }

    @Test public void rsiHandlesFlatRisingAndFallingSeries() {
        double[] flat = new double[40];
        double[] rising = new double[40];
        double[] falling = new double[40];
        for (int i = 0; i < 40; i++) {
            flat[i] = 100;
            rising[i] = 100 + i;
            falling[i] = 100 - i;
        }
        assertEquals(50, IndicatorEngine.rsi(flat, 14), 1e-9);
        assertEquals(100, IndicatorEngine.rsi(rising, 14), 1e-9);
        assertEquals(0, IndicatorEngine.rsi(falling, 14), 1e-9);
    }

    @Test public void flatMarketDoesNotProduceDirectionalSignal() {
        Fixture fixture = fixture(100, 0, 1.0);
        ScanResult result = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, "test");
        assertEquals(Signal.NEUTRAL, result.signal);
        assertEquals(MarketRegime.RANGE, result.regime);
        assertTrue(Math.abs(result.score) < 20);
    }

    @Test public void overextendedUptrendIsNotChased() {
        Fixture fixture = fixture(50, 0.0009, 1.0);
        ScanResult result = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, "test");
        assertEquals(Signal.NEUTRAL, result.signal);
        assertEquals(MarketRegime.TREND_UP, result.regime);
        assertTrue(result.adx14 >= 22);
        assertTrue(result.dataQuality >= 75);
        assertTrue(result.score >= 36);
        assertFalse(result.diagnostics.entryTimingPassed);
        assertTrue(result.objective1 > result.referencePrice);
        assertTrue(result.invalidation < result.referencePrice);
    }

    @Test public void oversoldDowntrendIsNotChased() {
        Fixture fixture = fixture(200, -0.0009, 1.0);
        ScanResult result = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, "test");
        assertEquals(Signal.NEUTRAL, result.signal);
        assertTrue(result.score <= -32);
        assertFalse(result.diagnostics.entryTimingPassed);
        assertTrue(result.objective1 < result.referencePrice);
        assertTrue(result.invalidation > result.referencePrice);
    }

    @Test public void pullbackInUptrendCanStillProduceBuyAssessment() {
        Fixture fixture = pullbackFixture();
        ScanResult result = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, "test");
        assertTrue(result.signal.isBuy());
        assertEquals(MarketRegime.TREND_UP, result.regime);
        assertTrue(result.diagnostics.entryTimingPassed);
        assertTrue(result.diagnostics.riskRewardPassed);
    }

    @Test public void lowQualityFeedAbstainsFromDirectionalSignal() {
        Fixture fixture = fixture(50, 0.0009, 1.0);
        MarketTicker lowQuality = new MarketTicker("TESTUSDT", "TESTUSDT", "TEST", "USDT",
                fixture.ticker.lastPrice, fixture.ticker.change24hPct, 100_000,
                System.currentTimeMillis(), 1, 5.0, 0, 0);
        ScanResult result = IndicatorEngine.analyze(lowQuality, fixture.five,
                fixture.fifteen, "test");
        assertTrue(result.technicalScore >= 28);
        assertTrue(result.dataQuality < 60);
        assertEquals(Signal.NEUTRAL, result.signal);
    }

    @Test public void abruptHighVolumeMoveIsPumpAlertNotBuy() {
        Fixture fixture = fixture(100, 0, 1.0);
        Candle previous = fixture.five.get(fixture.five.size() - 1);
        long width = 5L * 60L * 1000L;
        Candle jump = candle(previous.openTime + width, width, previous.close,
                previous.close * 1.045, 12.0);
        fixture.five.add(jump);
        ScanResult result = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, "test");
        assertEquals(Signal.PUMP_ALERT, result.signal);
        assertFalse(result.signal.isBuy());
        assertTrue(result.volumeRatio > 2);
        assertTrue(result.movementZScore > 3);
    }

    @Test public void incompleteCandleIsIgnored() {
        Fixture fixture = fixture(100, 0, 1.0);
        ScanResult baseline = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, "test");
        long now = System.currentTimeMillis();
        fixture.five.add(new Candle(now - 60_000, now + 4 * 60_000,
                baseline.referencePrice, baseline.referencePrice * 2,
                baseline.referencePrice, baseline.referencePrice * 2, 1_000, 1_000_000));
        ScanResult withIncomplete = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, "test");
        assertEquals(baseline.referencePrice, withIncomplete.referencePrice, 1e-9);
        assertEquals(baseline.signal, withIncomplete.signal);
    }

    @Test public void aggregatesOnlyCompleteFifteenMinuteBuckets() {
        Fixture fixture = fixture(100, 0.0002, 1.0);
        List<Candle> aggregated = IndicatorEngine.aggregateToFifteenMinutes(fixture.five);
        assertTrue(aggregated.size() >= fixture.five.size() / 3 - 2);
        for (Candle candle : aggregated) {
            assertTrue(candle.isValid());
            assertEquals(15L * 60L * 1000L - 1L,
                    candle.closeTime - candle.openTime);
        }
    }

    @Test public void liquidityIsAGateAndNeverAddsToScore() {
        Fixture fixture = pullbackFixture();
        List<Candle> one = trendCandles(90, 60, 100, 0.0015);
        List<Candle> four = trendCandles(90, 240, 100, 0.0020);
        OrderBookMetrics liquid = new OrderBookMetrics(true, 0.05,
                200_000, 200_000, 0, 0.03);
        OrderBookMetrics thin = new OrderBookMetrics(true, 1.2,
                2_000, 2_000, 0, 1.1);
        ScanResult accepted = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, one, four, 40, liquid, 0.10, 1.0, "test");
        ScanResult blocked = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, one, four, 40, thin, 0.10, 1.0, "test");
        assertTrue(accepted.signal.isBuy());
        assertEquals(accepted.score, blocked.score);
        assertEquals(accepted.technicalScore, blocked.technicalScore);
        assertFalse(blocked.diagnostics.liquidityPassed);
        assertEquals(Signal.NEUTRAL, blocked.signal);
    }

    @Test public void costAdjustedRiskRewardCanForceWait() {
        Fixture fixture = fixture(50, 0.0009, 1.0);
        List<Candle> one = trendCandles(90, 60, 100, 0.0015);
        List<Candle> four = trendCandles(90, 240, 100, 0.0020);
        OrderBookMetrics liquid = new OrderBookMetrics(true, 0.05,
                200_000, 200_000, 0, 0.20);
        ScanResult expensive = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, one, four, 40, liquid, 1.0, 1.0, "test");
        assertFalse(expensive.diagnostics.riskRewardPassed);
        assertEquals(Signal.NEUTRAL, expensive.signal);
        assertTrue(expensive.diagnostics.estimatedRoundTripCostPct >= 2.4);
    }

    @Test public void severeHigherTimeframeOppositionBlocksDirection() {
        Fixture fixture = fixture(50, 0.0009, 1.0);
        List<Candle> one = trendCandles(90, 60, 200, -0.0020);
        List<Candle> four = trendCandles(90, 240, 200, -0.0020);
        OrderBookMetrics liquid = new OrderBookMetrics(true, 0.05,
                200_000, 200_000, 0, 0.03);
        ScanResult result = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, one, four, -60, liquid, 0.10, 1.0, "test");
        assertTrue(result.technicalScore >= 28);
        assertTrue(result.diagnostics.oneHourTrend < -35);
        assertTrue(result.diagnostics.fourHourTrend < -35);
        assertFalse(result.diagnostics.higherTimeframeAligned);
        assertEquals(Signal.NEUTRAL, result.signal);
    }

    @Test public void chartContainsBoundedRealOhlcWindow() {
        Fixture fixture = fixture(50, 0.0003, 1.0);
        ScanResult result = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, "test");
        assertTrue(result.chartData.size() > 0);
        assertTrue(result.chartData.size() <= 120);
        int last = result.chartData.size() - 1;
        assertTrue(result.chartData.highs[last] >= result.chartData.opens[last]);
        assertTrue(result.chartData.lows[last] <= result.chartData.closes[last]);
    }

    private static List<Candle> trendCandles(int count, int intervalMinutes,
                                              double initial, double step) {
        List<Candle> candles = new ArrayList<>();
        long width = intervalMinutes * 60_000L;
        long start = System.currentTimeMillis() - (count + 2L) * width;
        double price = initial;
        for (int i = 0; i < count; i++) {
            double next = price * (1 + step);
            candles.add(candle(start + i * width, width, price, next, 1));
            price = next;
        }
        return candles;
    }

    private static Fixture pullbackFixture() {
        long width = 5L * 60L * 1000L;
        long fifteenWidth = 15L * 60L * 1000L;
        long end = System.currentTimeMillis() / fifteenWidth * fifteenWidth;
        long start = end - 240L * width;
        List<Candle> five = new ArrayList<>();
        double price = 100;
        for (int i = 0; i < 240; i++) {
            // Persistent uptrend with recurring consolidation and a fresh nine-candle
            // pullback: bullish context remains, but this is no longer a chase entry.
            double step = i >= 231 ? -0.0006 : (i % 5 == 4 ? -0.0003 : 0.0005);
            double next = price * (1 + step);
            double high = Math.max(price, next) * 1.003;
            double low = Math.min(price, next) * 0.997;
            five.add(new Candle(start + i * width, start + (i + 1) * width - 1,
                    price, high, low, next, 1_000, next * 1_000));
            price = next;
        }
        List<Candle> fifteen = IndicatorEngine.aggregateToFifteenMinutes(five);
        MarketTicker ticker = new MarketTicker("TESTUSDT", "TESTUSDT", "TEST", "USDT",
                price, 1, 50_000_000, System.currentTimeMillis());
        return new Fixture(ticker, five, fifteen);
    }

    private static Fixture fixture(double initial, double fiveStep, double volumeMultiplier) {
        long fiveWidth = 5L * 60L * 1000L;
        long fifteenWidth = 15L * 60L * 1000L;
        long end = System.currentTimeMillis() - 60_000L;
        long fiveStart = end - 181L * fiveWidth;
        long fifteenStart = end - 91L * fifteenWidth;
        List<Candle> five = new ArrayList<>();
        List<Candle> fifteen = new ArrayList<>();

        double price = initial;
        for (int i = 0; i < 180; i++) {
            double next = price * (1.0 + fiveStep);
            five.add(candle(fiveStart + i * fiveWidth, fiveWidth, price, next,
                    i == 179 ? volumeMultiplier : 1.0));
            price = next;
        }
        double fifteenStep = fiveStep * 3.0;
        // Both timeframes must end at the same market price despite covering different history.
        double fifteenPrice = price / Math.pow(1.0 + fifteenStep, 90);
        for (int i = 0; i < 90; i++) {
            double next = fifteenPrice * (1.0 + fifteenStep);
            fifteen.add(candle(fifteenStart + i * fifteenWidth, fifteenWidth,
                    fifteenPrice, next, 1.0));
            fifteenPrice = next;
        }
        MarketTicker ticker = new MarketTicker("TESTUSDT", "TESTUSDT", "TEST", "USDT",
                price, (price / initial - 1) * 100, 50_000_000, System.currentTimeMillis());
        return new Fixture(ticker, five, fifteen);
    }

    private static Candle candle(long start, long width, double open, double close,
                                 double volumeMultiplier) {
        double high = Math.max(open, close) * 1.001;
        double low = Math.min(open, close) * 0.999;
        double baseVolume = 1_000 * volumeMultiplier;
        return new Candle(start, start + width - 1, open, high, low, close,
                baseVolume, baseVolume * close);
    }

    private static final class Fixture {
        final MarketTicker ticker;
        final List<Candle> five;
        final List<Candle> fifteen;

        Fixture(MarketTicker ticker, List<Candle> five, List<Candle> fifteen) {
            this.ticker = ticker;
            this.five = five;
            this.fifteen = fifteen;
        }
    }
}
