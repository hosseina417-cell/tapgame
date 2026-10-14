package ai.arena.nabzcrypto.analysis;

import ai.arena.nabzcrypto.model.AiAssessment;
import ai.arena.nabzcrypto.model.Candle;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free deterministic regression check for the on-device classifier. */
public final class AiHarness {
    public static void main(String[] args) {
        AiAssessment first = OnDeviceAiAnalyzer.analyze(regimeCandles(210));
        AiAssessment second = OnDeviceAiAnalyzer.analyze(regimeCandles(210));
        check(first.trained, "model must train");
        check(first.reliable, "predictable holdout must be accepted");
        check(first.validationAccuracy > 0.58, "holdout accuracy must beat chance");
        check(first.probabilityUp < 0.5 && first.vote < 0,
                "end of repeating rising block should predict reversal");
        check(Math.abs(first.vote) <= 12, "AI vote must remain bounded");
        check(Math.abs(first.probabilityUp - second.probabilityUp) < 1e-12,
                "training must be deterministic");
        check(!OnDeviceAiAnalyzer.analyze(oneClassCandles(200)).trained,
                "one-class history must be rejected");
        System.out.println("PASS 7 deterministic on-device AI checks; accuracy="
                + first.validationAccuracy + " up=" + first.probabilityUp
                + " vote=" + first.vote);
    }

    private static List<Candle> regimeCandles(int count) {
        List<Candle> result = new ArrayList<>();
        long width = 5L * 60L * 1000L;
        long start = 1_700_000_000_000L;
        double price = 100;
        for (int i = 0; i < count; i++) {
            boolean rising = (i / 10) % 2 == 0;
            double next = price * (rising ? 1.003 : 0.997);
            result.add(candle(start + i * width, width, price, next,
                    1_000 * (1 + (i % 5) * 0.04)));
            price = next;
        }
        return result;
    }

    private static List<Candle> oneClassCandles(int count) {
        List<Candle> result = new ArrayList<>();
        long width = 5L * 60L * 1000L;
        long start = 1_700_000_000_000L;
        double price = 100;
        for (int i = 0; i < count; i++) {
            double next = price * 1.001;
            result.add(candle(start + i * width, width, price, next, 1_000));
            price = next;
        }
        return result;
    }

    private static Candle candle(long start, long width, double open, double close, double volume) {
        return new Candle(start, start + width - 1, open,
                Math.max(open, close) * 1.0005, Math.min(open, close) * 0.9995,
                close, volume, volume * close);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
