package ai.arena.nabzcrypto.data;

import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free deterministic verification for the multi-source consensus core. */
public final class ConsensusHarness {
    public static void main(String[] args) throws Exception {
        List<Object> snapshots = new ArrayList<>();
        snapshots.add(snapshot(new FakeProvider("A"), ticker(100, 10_000_000)));
        snapshots.add(snapshot(new FakeProvider("B"), ticker(102, 20_000_000)));
        snapshots.add(snapshot(new FakeProvider("OUTLIER"), ticker(200, 90_000_000)));

        Map<String, CoinMetadata> metadata = new HashMap<>();
        metadata.put("BTC", new CoinMetadata(1, 1_000_000_000_000d));
        Object bundle = bundle(snapshots, metadata);
        Method aggregate = MarketRepository.class.getDeclaredMethod("aggregate", bundle.getClass());
        aggregate.setAccessible(true);
        List<?> candidates = (List<?>) aggregate.invoke(null, bundle);
        check(candidates.size() == 1, "one canonical market expected");

        Field tickerField = candidates.get(0).getClass().getDeclaredField("ticker");
        tickerField.setAccessible(true);
        MarketTicker consensus = (MarketTicker) tickerField.get(candidates.get(0));
        check(Math.abs(consensus.lastPrice - 101d) < 1e-9, "median must ignore outlier");
        check(consensus.sourceCount == 2, "only two sane sources expected");
        check(Math.abs(consensus.quoteVolume24h - 30_000_000d) < 1e-6,
                "outlier volume must not leak into aggregate");
        check(consensus.priceSpreadPct > 1.97 && consensus.priceSpreadPct < 1.99,
                "spread must use accepted prices");
        check(consensus.marketCapRank == 1 && consensus.marketCapUsd == 1_000_000_000_000d,
                "CoinGecko metadata must enrich consensus");
        System.out.println("PASS 6 deterministic multi-source consensus checks");
    }

    private static Object snapshot(MarketProvider provider, MarketTicker ticker) throws Exception {
        Class<?> type = Class.forName(MarketRepository.class.getName() + "$SourceSnapshot");
        Constructor<?> constructor = type.getDeclaredConstructor(MarketProvider.class, List.class);
        constructor.setAccessible(true);
        List<MarketTicker> tickers = new ArrayList<>();
        tickers.add(ticker);
        return constructor.newInstance(provider, tickers);
    }

    private static Object bundle(List<Object> snapshots, Map<String, CoinMetadata> metadata)
            throws Exception {
        Class<?> type = Class.forName(MarketRepository.class.getName() + "$SourceBundle");
        Constructor<?> constructor = type.getDeclaredConstructor(List.class, Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(snapshots, metadata);
    }

    private static MarketTicker ticker(double price, double volume) {
        return new MarketTicker("BTCUSDT", "BTCUSDT", "BTC", "USDT",
                price, 2, volume, System.currentTimeMillis());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeProvider implements MarketProvider {
        private final String name;
        FakeProvider(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String tradingViewExchange() { return name; }
        @Override public List<MarketTicker> fetchTickers() { return new ArrayList<>(); }
        @Override public List<Candle> fetchCandles(MarketTicker ticker, int intervalMinutes, int limit) {
            return new ArrayList<>();
        }
    }
}
