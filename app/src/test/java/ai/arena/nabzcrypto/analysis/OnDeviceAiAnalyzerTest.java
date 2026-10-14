package ai.arena.nabzcrypto.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.arena.nabzcrypto.model.AiAssessment;
import ai.arena.nabzcrypto.model.Candle;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class OnDeviceAiAnalyzerTest {
    @Test public void learnsRepeatingRegimeWithoutUsingNetwork() {
        List<Candle> candles = regimeCandles(210);
        AiAssessment first = OnDeviceAiAnalyzer.analyze(candles);
        AiAssessment second = OnDeviceAiAnalyzer.analyze(candles);

        assertTrue(first.trained);
        assertTrue(first.reliable);
        assertTrue(first.validationSamples >= 24);
        assertTrue(first.validationAccuracy > 0.58);
        assertTrue(first.probabilityUp < 0.5);
        assertTrue(first.vote < 0 && first.vote >= -12);
        assertEquals(first.probabilityUp, second.probabilityUp, 1e-12);
        assertEquals(first.vote, second.vote);
    }

    @Test public void refusesOneClassHistoryInsteadOfInventingConfidence() {
        List<Candle> candles = new ArrayList<>();
        long width = 5L * 60L * 1000L;
        long start = System.currentTimeMillis() - 220L * width;
        double price = 100;
        for (int i = 0; i < 200; i++) {
            double next = price * 1.001;
            candles.add(candle(start + i * width, width, price, next, 1_000));
            price = next;
        }
        AiAssessment result = OnDeviceAiAnalyzer.analyze(candles);
        assertFalse(result.trained);
        assertFalse(result.reliable);
        assertEquals(0, result.vote);
        assertEquals(0.5, result.probabilityUp, 0);
    }

    private static List<Candle> regimeCandles(int count) {
        List<Candle> result = new ArrayList<>();
        long width = 5L * 60L * 1000L;
        long start = System.currentTimeMillis() - (count + 2L) * width;
        double price = 100;
        for (int i = 0; i < count; i++) {
            boolean rising = (i / 10) % 2 == 0;
            double next = price * (rising ? 1.003 : 0.997);
            double volume = 1_000 * (1 + (i % 5) * 0.04);
            result.add(candle(start + i * width, width, price, next, volume));
            price = next;
        }
        return result;
    }

    private static Candle candle(long start, long width, double open, double close, double volume) {
        return new Candle(start, start + width - 1, open,
                Math.max(open, close) * 1.0005, Math.min(open, close) * 0.9995,
                close, volume, volume * close);
    }
}
