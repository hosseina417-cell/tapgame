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
        List<Candle> candles = regimeCandles(256);
        AiAssessment first = OnDeviceAiAnalyzer.analyze(candles);
        AiAssessment second = OnDeviceAiAnalyzer.analyze(candles);

        assertTrue(first.trained);
        assertTrue(first.reliable);
        assertTrue(first.validationSamples >= 24);
        assertTrue(first.validationAccuracy > 0.58);
        assertEquals(1.0, first.probabilityUp + first.probabilityDown
                + first.probabilityNeutral, 1e-12);
        assertEquals(2, first.modelsUsed);
        assertTrue(first.validationFolds >= 4);
        assertTrue(first.horizonAgreement);
        assertTrue(first.probabilityUp > first.probabilityDown);
        assertTrue(first.vote > 0 && first.vote <= 12);
        assertEquals(first.probabilityUp, second.probabilityUp, 1e-12);
        assertEquals(first.vote, second.vote);
    }

    @Test public void twoValidHorizonsAbstainAtNeutralTransition() {
        AiAssessment assessment = OnDeviceAiAnalyzer.analyze(regimeCandles(262));
        assertTrue(assessment.trained);
        assertTrue(assessment.reliable);
        assertEquals(2, assessment.modelsUsed);
        assertFalse(assessment.horizonAgreement);
        assertEquals(0, assessment.vote);
        assertTrue(assessment.probabilityNeutral > 0.50);
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
        assertEquals(1.0 / 3.0, result.probabilityUp, 1e-12);
        assertEquals(1.0 / 3.0, result.probabilityDown, 1e-12);
        assertEquals(1.0 / 3.0, result.probabilityNeutral, 1e-12);
    }

    @Test public void validatedRecentDriftDisablesEntireAiVote() {
        AiAssessment result = OnDeviceAiAnalyzer.analyze(driftCandles(500, 80));
        assertTrue(result.trained);
        assertTrue(result.driftDetected);
        assertFalse(result.reliable);
        assertEquals(0, result.vote);
        assertTrue(result.validationAccuracy - result.recentValidationAccuracy >= 0.18);
    }

    private static List<Candle> driftCandles(int count, int noisyTail) {
        List<Candle> result = new ArrayList<>();
        long width = 5L * 60L * 1000L;
        long start = System.currentTimeMillis() - (count + 2L) * width;
        long random = 1_234_567L;
        double price = 100;
        for (int i = 0; i < count; i++) {
            double step;
            if (i < count - noisyTail) {
                int phase = i % 36;
                step = phase < 10 ? 0.003 : phase < 18 ? 0
                        : phase < 28 ? -0.003 : 0;
            } else {
                random = (random * 1_103_515_245L + 12_345L) & 0x7fffffffL;
                step = ((random % 3L) - 1L) * 0.003;
            }
            double next = price * (1 + step);
            result.add(candle(start + i * width, width, price, next,
                    1_000 * (1 + (i % 5) * 0.04)));
            price = next;
        }
        return result;
    }

    private static List<Candle> regimeCandles(int count) {
        List<Candle> result = new ArrayList<>();
        long width = 5L * 60L * 1000L;
        long start = System.currentTimeMillis() - (count + 2L) * width;
        double price = 100;
        for (int i = 0; i < count; i++) {
            int phase = i % 36;
            double step = phase < 10 ? 0.003 : phase < 18 ? 0
                    : phase < 28 ? -0.003 : 0;
            double next = price * (1.0 + step);
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
