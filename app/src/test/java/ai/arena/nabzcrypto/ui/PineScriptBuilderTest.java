package ai.arena.nabzcrypto.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.arena.nabzcrypto.model.AiAssessment;
import ai.arena.nabzcrypto.model.MarketRegime;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;

import org.junit.Test;

import java.util.Collections;

public final class PineScriptBuilderTest {
    @Test public void exportsExactStaticLevelsWithoutScientificNotation() {
        String script = PineScriptBuilder.build("btc/usdt", false,
                67234.125, 67000, 67400, 66120.5, 68900, 70250,
                1_700_000_000_000L);
        assertTrue(script.startsWith("//@version=5"));
        assertTrue(script.contains("NabzCrypto BTCUSDT Trade Map"));
        assertTrue(script.contains("input.float(67000.0, \"Entry zone low\")"));
        assertTrue(script.contains("input.float(66120.5, \"Invalidation / stop\")"));
        assertTrue(script.contains("input.float(70250.0, \"Statistical target 2\")"));
        assertTrue(script.contains("UP SCENARIO"));
        assertTrue(script.contains("fill(entryLowPlot, entryHighPlot"));
        assertFalse(script.contains("E4"));
    }

    @Test public void labelsBearishPlanAndSanitizesSymbol() {
        String script = PineScriptBuilder.build("1000pepe-usdt", true,
                0.009, 0.0088, 0.0091, 0.0095, 0.0082, 0.0077, 42);
        assertTrue(script.contains("1000PEPEUSDT"));
        assertTrue(script.contains("DOWN SCENARIO"));
        assertTrue(script.contains("input.float(0.0077, \"Statistical target 2\")"));
    }

    @Test public void neutralResultIsExportedAsWatchOnlyNotEntrySignal() {
        MarketTicker ticker = new MarketTicker("TESTUSDT", "TESTUSDT", "TEST", "USDT",
                100, 0, 10_000_000, 42);
        ScanResult result = new ScanResult(ticker, Signal.NEUTRAL, 0, 0,
                50, 100, AiAssessment.unavailable(), MarketRegime.RANGE, 10,
                50, 100, 100, 100, 0, 1,
                0, 0, 0, 1, 0, 100,
                99.75, 100.15, 99, 101.5, 102.5, 42,
                "test", Collections.emptyList(), Collections.emptyList(),
                new double[]{99, 100}, false);
        String script = PineScriptBuilder.build(result);
        assertTrue(script.contains("WAIT - NO ENTRY SIGNAL"));
        assertTrue(script.contains("planColor = color.orange"));
        assertTrue(PineScriptBuilder.summary(result).contains("بدون سیگنال ورود"));
    }
}
