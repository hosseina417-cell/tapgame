package ai.arena.nabzcrypto.data;

import ai.arena.nabzcrypto.model.Candle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CandleNormalizer {
    private CandleNormalizer() {}

    static List<Candle> completed(List<Candle> input, int limit) {
        long now = System.currentTimeMillis();
        input.sort(Comparator.comparingLong(c -> c.openTime));
        Map<Long, Candle> unique = new LinkedHashMap<>();
        for (Candle candle : input) {
            if (candle != null && candle.isValid() && candle.closeTime < now - 750L) {
                unique.put(candle.openTime, candle);
            }
        }
        List<Candle> result = new ArrayList<>(unique.values());
        if (result.size() > limit) {
            return new ArrayList<>(result.subList(result.size() - limit, result.size()));
        }
        return result;
    }

    static double number(String text) {
        if (text == null) return Double.NaN;
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }
}
