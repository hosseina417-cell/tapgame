package ai.arena.nabzcrypto.data;

import android.os.Handler;
import android.os.Looper;

import ai.arena.nabzcrypto.analysis.IndicatorEngine;
import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.ScanConfig;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates provider failover and a bounded, rate-limit-friendly candle scan. */
public final class MarketRepository {
    public interface Callback {
        void onStarted();
        void onSourceSelected(String source, int marketCount);
        void onProgress(int completed, int total, ScanResult newResult);
        void onCompleted(List<ScanResult> results, String source, int failedMarkets);
        void onError(String message);
    }

    private static final int CANDLE_WORKERS = 5;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private final List<MarketProvider> providers = Arrays.asList(
            new BinanceProvider(), new KucoinProvider());
    private final AtomicBoolean scanning = new AtomicBoolean(false);

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
            SourceSnapshot snapshot = fastestSource();
            List<MarketTicker> selected = selectMarkets(snapshot.tickers, config, favorites);
            if (selected.isEmpty()) throw new ApiException("بازار واجد شرایطی پیدا نشد");
            post(() -> callback.onSourceSelected(snapshot.provider.name(), selected.size()));

            workers = Executors.newFixedThreadPool(CANDLE_WORKERS);
            CompletionService<ScanResult> completion = new ExecutorCompletionService<>(workers);
            for (MarketTicker ticker : selected) {
                completion.submit(() -> analyze(snapshot.provider, ticker));
            }

            List<ScanResult> results = new ArrayList<>();
            int failed = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(155);
            for (int completed = 1; completed <= selected.size(); completed++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    failed += selected.size() - completed + 1;
                    break;
                }
                Future<ScanResult> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) {
                    failed += selected.size() - completed + 1;
                    break;
                }
                ScanResult result = null;
                try {
                    result = future.get();
                    if (result != null) results.add(result);
                    else failed++;
                } catch (ExecutionException e) {
                    failed++;
                }
                final int doneForUi = completed;
                final int failedForUi = failed;
                final ScanResult resultForUi = result;
                post(() -> callback.onProgress(doneForUi, selected.size(), resultForUi));
            }

            sortResults(results);
            if (results.isEmpty()) {
                throw new ApiException("کندل کافی دریافت نشد؛ منبع بازار یا اینترنت را بررسی کنید");
            }
            final List<ScanResult> immutable = Collections.unmodifiableList(new ArrayList<>(results));
            final int failedFinal = failed;
            post(() -> callback.onCompleted(immutable, snapshot.provider.name(), failedFinal));
        } catch (ApiException e) {
            String message = friendly(e.getMessage());
            post(() -> callback.onError(message));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            post(() -> callback.onError("اسکن متوقف شد"));
        } catch (RuntimeException e) {
            post(() -> callback.onError("خطای غیرمنتظره در تحلیل بازار"));
        } finally {
            if (workers != null) workers.shutdownNow();
            scanning.set(false);
        }
    }

    private SourceSnapshot fastestSource() throws ApiException, InterruptedException {
        ExecutorService discovery = Executors.newFixedThreadPool(providers.size());
        CompletionService<SourceSnapshot> completion = new ExecutorCompletionService<>(discovery);
        List<Future<SourceSnapshot>> futures = new ArrayList<>();
        for (MarketProvider provider : providers) {
            futures.add(completion.submit(() -> new SourceSnapshot(provider, provider.fetchTickers())));
        }
        List<String> errors = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(32);
        try {
            for (int i = 0; i < providers.size(); i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<SourceSnapshot> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) break;
                try {
                    SourceSnapshot snapshot = future.get();
                    if (snapshot.tickers.size() >= 10) return snapshot;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause != null && cause.getMessage() != null) errors.add(cause.getMessage());
                }
            }
        } finally {
            for (Future<SourceSnapshot> future : futures) future.cancel(true);
            discovery.shutdownNow();
        }
        String detail = errors.isEmpty() ? "مهلت اتصال تمام شد" : friendly(errors.get(0));
        throw new ApiException("هیچ منبع عمومی در دسترس نیست: " + detail);
    }

    private ScanResult analyze(MarketProvider provider, MarketTicker ticker) throws ApiException {
        List<Candle> five = provider.fetchCandles(ticker, 5, 180);
        if (five.size() < 55) throw new ApiException("کندل ۵دقیقه کافی نیست");
        List<Candle> fifteen;
        try {
            fifteen = provider.fetchCandles(ticker, 15, 90);
        } catch (ApiException e) {
            fifteen = IndicatorEngine.aggregateToFifteenMinutes(five);
        }
        if (fifteen.size() < 55) {
            // A second aggregation path also covers structurally empty provider responses.
            fifteen = IndicatorEngine.aggregateToFifteenMinutes(five);
        }
        try {
            return IndicatorEngine.analyze(ticker, five, fifteen, provider.name());
        } catch (IllegalArgumentException e) {
            throw new ApiException("دادهٔ کافی برای تحلیل " + ticker.baseAsset + " وجود ندارد");
        }
    }

    private static List<MarketTicker> selectMarkets(List<MarketTicker> all, ScanConfig config,
                                                     Set<String> favorites) {
        List<MarketTicker> sorted = new ArrayList<>();
        Map<String, MarketTicker> bySymbol = new HashMap<>();
        for (MarketTicker ticker : all) {
            bySymbol.put(ticker.symbol, ticker);
            if (MarketFilters.eligible(ticker, config.minimumQuoteVolume)) sorted.add(ticker);
        }
        sorted.sort((a, b) -> Double.compare(b.quoteVolume24h, a.quoteVolume24h));
        LinkedHashMap<String, MarketTicker> chosen = new LinkedHashMap<>();
        for (MarketTicker ticker : sorted) {
            if (chosen.size() >= config.marketLimit) break;
            chosen.put(ticker.symbol, ticker);
        }
        if (favorites != null) {
            for (String symbol : favorites) {
                MarketTicker ticker = bySymbol.get(symbol);
                if (MarketFilters.eligible(ticker, 0)) chosen.put(ticker.symbol, ticker);
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

    private void post(Runnable runnable) {
        main.post(runnable);
    }

    private static String friendly(String message) {
        if (message == null || message.trim().isEmpty()) return "خطای اتصال به بازار";
        String cleaned = message.replace('\n', ' ').trim();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }

    private static final class SourceSnapshot {
        final MarketProvider provider;
        final List<MarketTicker> tickers;

        SourceSnapshot(MarketProvider provider, List<MarketTicker> tickers) {
            this.provider = provider;
            this.tickers = tickers;
        }
    }
}
