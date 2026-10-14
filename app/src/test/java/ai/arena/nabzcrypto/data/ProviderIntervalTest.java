package ai.arena.nabzcrypto.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ProviderIntervalTest {
    @Test public void allProvidersMapOneAndFourHourIntervals() {
        assertEquals("1h", BinanceProvider.intervalToken(60));
        assertEquals("4h", BinanceProvider.intervalToken(240));
        assertEquals("1hour", KucoinProvider.intervalToken(60));
        assertEquals("4hour", KucoinProvider.intervalToken(240));
        assertEquals("60", BybitProvider.intervalToken(60));
        assertEquals("240", BybitProvider.intervalToken(240));
        assertEquals("1H", OkxProvider.intervalToken(60));
        assertEquals("4H", OkxProvider.intervalToken(240));
    }

    @Test public void unsupportedIntervalsAreRejectedBeforeNetwork() {
        assertEquals(null, BinanceProvider.intervalToken(7));
        assertEquals(null, KucoinProvider.intervalToken(7));
        assertEquals(null, BybitProvider.intervalToken(7));
        assertEquals(null, OkxProvider.intervalToken(7));
    }
}
