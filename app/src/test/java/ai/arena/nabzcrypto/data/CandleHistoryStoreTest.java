package ai.arena.nabzcrypto.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ai.arena.nabzcrypto.model.Candle;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CandleHistoryStoreTest {
    @Test public void incrementalMergeDeduplicatesSortsAndKeepsNewestBound() {
        List<Candle> cached = new ArrayList<>();
        for (int i = 0; i < 8; i++) cached.add(candle(i, 100 + i));
        List<Candle> fresh = Arrays.asList(candle(7, 999), candle(8, 108), candle(9, 109));
        List<Candle> merged = CandleHistoryStore.mergeBounded(cached, fresh, 7);
        assertEquals(7, merged.size());
        assertEquals(3L * 300_000L, merged.get(0).openTime);
        assertEquals(9L * 300_000L, merged.get(6).openTime);
        assertEquals(999, merged.get(4).close, 1e-9);
        for (int i = 1; i < merged.size(); i++) {
            assertTrue(merged.get(i).openTime > merged.get(i - 1).openTime);
        }
    }

    @Test public void invalidCandlesNeverEnterCache() {
        Candle invalid = new Candle(0, 1, 0, 0, 0, 0, 0, 0);
        List<Candle> merged = CandleHistoryStore.mergeBounded(
                Arrays.asList(candle(1, 101), invalid), null, 10);
        assertEquals(1, merged.size());
    }

    private static Candle candle(int index, double close) {
        long open = index * 300_000L;
        return new Candle(open, open + 299_999L, close, close + 1,
                close - 1, close, 100, close * 100);
    }
}
