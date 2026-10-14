package ai.arena.nabzcrypto.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;
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
        assertTrue(Math.abs(result.score) < 20);
    }

    @Test public void alignedUptrendProducesBuyAssessment() {
        Fixture fixture = fixture(50, 0.0009, 1.0);
        ScanResult result = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, "test");
        assertTrue(result.signal.isBuy());
        assertTrue(result.score >= 32);
        assertTrue(result.objective1 > result.referencePrice);
        assertTrue(result.invalidation < result.referencePrice);
    }

    @Test public void alignedDowntrendProducesSellAssessment() {
        Fixture fixture = fixture(200, -0.0009, 1.0);
        ScanResult result = IndicatorEngine.analyze(fixture.ticker, fixture.five,
                fixture.fifteen, "test");
        assertTrue(result.signal.isSell());
        assertTrue(result.score <= -32);
        assertTrue(result.objective1 < result.referencePrice);
        assertTrue(result.invalidation > result.referencePrice);
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
        double fifteenPrice = initial;
        double fifteenStep = fiveStep * 3.0;
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
