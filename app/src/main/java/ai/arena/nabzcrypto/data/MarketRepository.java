package ai.arena.nabzcrypto.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import ai.arena.nabzcrypto.analysis.IndicatorEngine;
import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.OrderBookMetrics;
import ai.arena.nabzcrypto.model.ScanConfig;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates four public exchange feeds, robust price consensus and bounded candle scans. */
public final class MarketRepository {
    public interface Callback {
        void onStarted();
        void onSourceSelected(String source, int marketCount);
        void onProgress(int completed, int total, ScanResult newResult);
        void onCompleted(List<ScanResult> results, String source, int failedMarkets);
        void onError(String message);
    }

    private static final int CANDLE_WORKERS = 5;
    private static final int MAX_NEW_CANDIDATES_PER_SCAN = 8;
    private static final long SOURCE_DISCOVERY_SECONDS = 20;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private final List<MarketProvider> providers = Arrays.asList(
            new BinanceProvider(), new KucoinProvider(), new BybitProvider(), new OkxProvider());
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final CandleHistoryStore historyStore;
    private final SignalJournal journal;

    public MarketRepository(Context context, SignalJournal journal) {
        this.historyStore = new CandleHistoryStore(context.getApplicationContext());
        this.journal = journal;
    }

    public boolean isScanning() {
        return scanning.get();
    }

    public void scan(ScanConfig config, Set<String> favorites, Callback callback) {
        if (!scanning.compareAndSet(false, true)) return;
        post(callback::onStarted);
        coordinator.submit(() -> runScan(config, favorites, callback));
    }

    public void shutdown() {
        coordinator.shutdownNow();
    }

    private void runScan(ScanConfig config, Set<String> favorites, Callback callback) {
        ExecutorService workers = null;
        try {
            SourceBundle bundle = discoverSources();
            List<MarketCandidate> universe = aggregate(bundle);
            Set<String> required = new HashSet<>();
            if (favorites != null) required.addAll(favorites);
            if (journal != null) required.addAll(journal.activeSymbols());
            List<MarketCandidate> selected = selectMarkets(universe, config, required);
            if (selected.isEmpty()) throw new ApiException("بازار واجد شرایطی پیدا نشد");
            int benchmarkTrend = benchmarkTrend(universe);
            String sourceLabel = sourceLabel(bundle) + " • زمینه بازار "
                    + String.format(Locale.US, "%+d", benchmarkTrend);
            post(() -> callback.onSourceSelected(sourceLabel, selected.size()));

            workers = Executors.newFixedThreadPool(CANDLE_WORKERS);
            CompletionService<AnalyzedMarket> completion = new ExecutorCompletionService<>(workers);
            for (MarketCandidate candidate : selected) {
                boolean favorite = favorites != null && favorites.contains(candidate.ticker.symbol);
                completion.submit(() -> analyze(candidate, config, benchmarkTrend, favorite));
            }

            List<ScanResult> analyzed = new ArrayList<>();
            Map<String, List<Candle>> journalWindows = new HashMap<>();
            int failed = 0;
            long seconds = Math.max(210L, selected.size() * 5L);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(420L, seconds));
            for (int completed = 1; completed <= selected.size(); completed++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    failed += selected.size() - completed + 1;
                    break;
                }
                Future<AnalyzedMarket> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) {
                    failed += selected.size() - completed + 1;
                    break;
                }
                ScanResult result = null;
                try {
                    AnalyzedMarket outcome = future.get();
                    if (outcome != null && outcome.result != null) {
                        result = outcome.result;
                        analyzed.add(result);
                        journalWindows.put(result.ticker.symbol, outcome.journalCandles);
                    } else {
                        failed++;
                    }
                } catch (ExecutionException e) {
                    failed++;
                }
                final int doneForUi = completed;
                final ScanResult resultForUi = result;
                post(() -> callback.onProgress(doneForUi, selected.size(), resultForUi));
            }

            sortResults(analyzed);
            if (journal != null) {
                int newBudget = MAX_NEW_CANDIDATES_PER_SCAN;
                for (ScanResult result : analyzed) {
                    // Lifecycle evaluation always runs. The flag only controls whether a
                    // terminal/opposite transition may arm a fresh candidate, and every new
                    // candidate (including a replacement) consumes the same scan budget.
                    boolean created = journal.observe(result,
                            journalWindows.get(result.ticker.symbol), newBudget > 0);
                    if (created && newBudget > 0) newBudget--;
                }
                journal.flush();
            }
            if (analyzed.isEmpty()) {
                throw new ApiException("کندل کافی دریافت نشد؛ منابع بازار یا اینترنت را بررسی کنید");
            }
            final List<ScanResult> immutable = Collections.unmodifiableList(new ArrayList<>(analyzed));
            final int failedFinal = failed;
            post(() -> callback.onCompleted(immutable, sourceLabel, failedFinal));
        } catch (ApiException e) {
            String message = friendly(e.getMessage());
            post(() -> callback.onError(message));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            post(() -> callback.onError("اسکن متوقف شد"));
        } catch (RuntimeException e) {
            post(() -> callback.onError("خطای غیرمنتظره در تجمیع و تحلیل بازار"));
        } finally {
            if (workers != null) workers.shutdownNow();
            if (journal != null) journal.flush();
            scanning.set(false);
        }
    }

    private SourceBundle discoverSources() throws ApiException, InterruptedException {
        ExecutorService discovery = Executors.newFixedThreadPool(providers.size() + 1);
        CompletionService<SourceSnapshot> completion = new ExecutorCompletionService<>(discovery);
        List<Future<SourceSnapshot>> providerFutures = new ArrayList<>();
        for (MarketProvider provider : providers) {
            providerFutures.add(completion.submit(
                    () -> new SourceSnapshot(provider, provider.fetchTickers())));
        }
        Future<Map<String, CoinMetadata>> metadataFuture = discovery.submit(
                () -> new CoinGeckoMetadataProvider().fetch());
        List<SourceSnapshot> snapshots = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SOURCE_DISCOVERY_SECONDS);
        try {
            for (int i = 0; i < providers.size(); i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                long wait = snapshots.size() >= 2
                        ? Math.min(remaining, TimeUnit.SECONDS.toNanos(8)) : remaining;
                Future<SourceSnapshot> future = completion.poll(wait, TimeUnit.NANOSECONDS);
                if (future == null) break;
                try {
                    SourceSnapshot snapshot = future.get();
                    if (snapshot.tickers.size() >= 10) snapshots.add(snapshot);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause != null && cause.getMessage() != null) errors.add(cause.getMessage());
                }
            }
            if (snapshots.isEmpty()) {
                String detail = errors.isEmpty() ? "مهلت اتصال تمام شد" : friendly(errors.get(0));
                throw new ApiException("هیچ صرافی عمومی در دسترس نیست: " + detail);
            }
            Map<String, CoinMetadata> metadata = Collections.emptyMap();
            try {
                long remaining = Math.max(1, deadline - System.nanoTime());
                metadata = metadataFuture.get(Math.min(remaining, TimeUnit.SECONDS.toNanos(2)),
                        TimeUnit.NANOSECONDS);
            } catch (Exception ignored) {
                // CoinGecko is enrichment only; exchange scanning must continue without it.
            }
            return new SourceBundle(snapshots, metadata);
        } finally {
            for (Future<SourceSnapshot> future : providerFutures) future.cancel(true);
            metadataFuture.cancel(true);
            discovery.shutdownNow();
        }
    }

    private static List<MarketCandidate> aggregate(SourceBundle bundle) {
        Map<String, List<ProviderQuote>> grouped = new HashMap<>();
        for (SourceSnapshot snapshot : bundle.snapshots) {
            for (MarketTicker ticker : snapshot.tickers) {
                if (!MarketFilters.eligible(ticker, 0)) continue;
                grouped.computeIfAbsent(ticker.symbol, ignored -> new ArrayList<>())
                        .add(new ProviderQuote(snapshot.provider, ticker));
            }
        }

        List<MarketCandidate> result = new ArrayList<>();
        for (Map.Entry<String, List<ProviderQuote>> entry : grouped.entrySet()) {
            List<ProviderQuote> quotes = entry.getValue();
            double[] allPrices = new double[quotes.size()];
            for (int i = 0; i < quotes.size(); i++) allPrices[i] = quotes.get(i).ticker.lastPrice;
            double roughMedian = median(allPrices);
            List<ProviderQuote> sane = new ArrayList<>();
            for (ProviderQuote quote : quotes) {
                double deviation = Math.abs(quote.ticker.lastPrice / roughMedian - 1.0);
                if (deviation <= 0.08) sane.add(quote);
            }
            if (sane.isEmpty()) continue;
            sane.sort((a, b) -> Double.compare(b.ticker.quoteVolume24h, a.ticker.quoteVolume24h));

            double[] prices = new double[sane.size()];
            double[] changes = new double[sane.size()];
            double totalVolume = 0;
            double minPrice = Double.MAX_VALUE;
            double maxPrice = 0;
            for (int i = 0; i < sane.size(); i++) {
                MarketTicker ticker = sane.get(i).ticker;
                prices[i] = ticker.lastPrice;
                changes[i] = ticker.change24hPct;
                totalVolume += ticker.quoteVolume24h;
                minPrice = Math.min(minPrice, ticker.lastPrice);
                maxPrice = Math.max(maxPrice, ticker.lastPrice);
            }
            double consensusPrice = median(prices);
            double spread = consensusPrice > 0
                    ? (maxPrice - minPrice) / consensusPrice * 100.0 : 0;
            ProviderQuote primary = sane.get(0);
            CoinMetadata metadata = bundle.metadata.get(primary.ticker.baseAsset.toUpperCase(Locale.US));
            int rank = metadata == null ? 0 : metadata.marketCapRank;
            double marketCap = metadata == null ? 0 : metadata.marketCapUsd;
            MarketTicker consensus = new MarketTicker(primary.ticker.symbol,
                    primary.ticker.apiSymbol, primary.ticker.baseAsset, "USDT",
                    consensusPrice, median(changes), totalVolume, System.currentTimeMillis(),
                    sane.size(), spread, rank, marketCap);
            result.add(new MarketCandidate(consensus, sane));
        }
        return result;
    }

    private AnalyzedMarket analyze(MarketCandidate candidate, ScanConfig config,
                                   int benchmarkTrend, boolean favorite) throws ApiException {
        ApiException lastError = null;
        int attempts = Math.min(3, candidate.alternatives.size());
        for (int i = 0; i < attempts; i++) {
            ProviderQuote quote = candidate.alternatives.get(i);
            try {
                List<Candle> freshFive = quote.provider.fetchCandles(quote.ticker, 5, 1000);
                if (freshFive.size() < 55) throw new ApiException("کندل ۵دقیقه کافی نیست");
                List<Candle> five = historyStore.merge(candidate.ticker.symbol,
                        freshFive, favorite);
                List<Candle> fifteen = safeCandles(quote.provider, quote.ticker, 15, 300);
                if (fifteen.size() < 55) fifteen = IndicatorEngine.aggregateToFifteenMinutes(five);
                List<Candle> oneHour = safeCandles(quote.provider, quote.ticker, 60, 168);
                if (oneHour.size() < 40) {
                    oneHour = IndicatorEngine.aggregateCandles(fifteen, 15, 60);
                }
                List<Candle> fourHour = safeCandles(quote.provider, quote.ticker, 240, 180);
                if (fourHour.size() < 40) {
                    fourHour = IndicatorEngine.aggregateCandles(oneHour, 60, 240);
                }
                OrderBookMetrics orderBook;
                try {
                    orderBook = quote.provider.fetchOrderBook(quote.ticker);
                } catch (ApiException ignored) {
                    orderBook = OrderBookMetrics.unavailable();
                }
                String source = quote.provider.name() + " • اجماع "
                        + candidate.ticker.sourceCount + " منبع";
                ScanResult result = IndicatorEngine.analyze(candidate.ticker, five, fifteen,
                        oneHour, fourHour, benchmarkTrend, orderBook,
                        config.feePerSidePct, config.riskPerTradePct, source);
                return new AnalyzedMarket(result, journalTail(five));
            } catch (ApiException e) {
                lastError = e;
            } catch (IllegalArgumentException e) {
                lastError = new ApiException("دادهٔ کافی برای " + candidate.ticker.baseAsset + " نیست");
            }
        }
        throw lastError == null ? new ApiException("تحلیل بازار ممکن نشد") : lastError;
    }

    private static List<Candle> safeCandles(MarketProvider provider, MarketTicker ticker,
                                            int interval, int limit) {
        try {
            return provider.fetchCandles(ticker, interval, limit);
        } catch (ApiException ignored) {
            return new ArrayList<>();
        }
    }

    private static List<Candle> journalTail(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return new ArrayList<>();
        int start = Math.max(0, candles.size() - 200);
        return new ArrayList<>(candles.subList(start, candles.size()));
    }

    private static int benchmarkTrend(List<MarketCandidate> universe) {
        int sum = 0;
        int count = 0;
        for (MarketCandidate candidate : universe) {
            String base = candidate.ticker.baseAsset;
            if (!("BTC".equals(base) || "ETH".equals(base))
                    || candidate.alternatives.isEmpty()) continue;
            ProviderQuote quote = candidate.alternatives.get(0);
            List<Candle> oneHour = safeCandles(quote.provider, quote.ticker, 60, 168);
            List<Candle> fourHour = safeCandles(quote.provider, quote.ticker, 240, 180);
            int one = IndicatorEngine.timeframeTrend(oneHour);
            int four = IndicatorEngine.timeframeTrend(fourHour);
            if (oneHour.size() >= 40 || fourHour.size() >= 40) {
                sum += Math.round(one * 0.45f + four * 0.55f);
                count++;
            }
        }
        return count == 0 ? 0 : Math.max(-100, Math.min(100, Math.round(sum / (float) count)));
    }

    private static List<MarketCandidate> selectMarkets(List<MarketCandidate> all,
                                                        ScanConfig config,
                                                        Set<String> favorites) {
        List<MarketCandidate> sorted = new ArrayList<>();
        Map<String, MarketCandidate> bySymbol = new HashMap<>();
        for (MarketCandidate candidate : all) {
            bySymbol.put(candidate.ticker.symbol, candidate);
            if (MarketFilters.eligible(candidate.ticker, config.minimumQuoteVolume)) {
                sorted.add(candidate);
            }
        }
        sorted.sort((a, b) -> Double.compare(b.ticker.quoteVolume24h, a.ticker.quoteVolume24h));
        LinkedHashMap<String, MarketCandidate> chosen = new LinkedHashMap<>();
        for (MarketCandidate candidate : sorted) {
            if (chosen.size() >= config.marketLimit) break;
            chosen.put(candidate.ticker.symbol, candidate);
        }
        if (favorites != null) {
            for (String rawSymbol : favorites) {
                String symbol = rawSymbol == null ? "" : rawSymbol.toUpperCase(Locale.US);
                MarketCandidate candidate = bySymbol.get(symbol);
                if (candidate != null && MarketFilters.eligible(candidate.ticker, 0)) {
                    chosen.put(symbol, candidate);
                }
            }
        }
        return new ArrayList<>(chosen.values());
    }

    public static void sortResults(List<ScanResult> results) {
        results.sort((a, b) -> {
            int category = Integer.compare(priority(b.signal), priority(a.signal));
            if (category != 0) return category;
            int strength = Integer.compare(b.evidenceStrength, a.evidenceStrength);
            if (strength != 0) return strength;
            return Double.compare(b.ticker.quoteVolume24h, a.ticker.quoteVolume24h);
        });
    }

    private static int priority(Signal signal) {
        if (signal.isMovementAlert()) return 5;
        if (signal == Signal.STRONG_BUY || signal == Signal.STRONG_SELL) return 4;
        if (signal == Signal.BUY || signal == Signal.SELL) return 3;
        if (signal == Signal.NEUTRAL) return 2;
        return 1;
    }

    private static double median(double[] values) {
        if (values.length == 0) return 0;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0 : sorted[middle];
    }

    private static String sourceLabel(SourceBundle bundle) {
        String label = bundle.snapshots.size() + " صرافی عمومی";
        if (!bundle.metadata.isEmpty()) label += " + CoinGecko";
        return label;
    }

    private void post(Runnable runnable) {
        main.post(runnable);
    }

    private static String friendly(String message) {
        if (message == null || message.trim().isEmpty()) return "خطای اتصال به بازار";
        String cleaned = message.replace('\n', ' ').trim();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }

    private static final class AnalyzedMarket {
        final ScanResult result;
        final List<Candle> journalCandles;

        AnalyzedMarket(ScanResult result, List<Candle> journalCandles) {
            this.result = result;
            this.journalCandles = journalCandles == null
                    ? Collections.emptyList() : journalCandles;
        }
    }

    private static final class ProviderQuote {
        final MarketProvider provider;
        final MarketTicker ticker;

        ProviderQuote(MarketProvider provider, MarketTicker ticker) {
            this.provider = provider;
            this.ticker = ticker;
        }
    }

    private static final class MarketCandidate {
        final MarketTicker ticker;
        final List<ProviderQuote> alternatives;

        MarketCandidate(MarketTicker ticker, List<ProviderQuote> alternatives) {
            this.ticker = ticker;
            this.alternatives = alternatives;
        }
    }

    private static final class SourceSnapshot {
        final MarketProvider provider;
        final List<MarketTicker> tickers;

        SourceSnapshot(MarketProvider provider, List<MarketTicker> tickers) {
            this.provider = provider;
            this.tickers = tickers;
        }
    }

    private static final class SourceBundle {
        final List<SourceSnapshot> snapshots;
        final Map<String, CoinMetadata> metadata;

        SourceBundle(List<SourceSnapshot> snapshots, Map<String, CoinMetadata> metadata) {
            this.snapshots = snapshots;
            this.metadata = metadata == null ? Collections.emptyMap() : metadata;
        }
    }
}
