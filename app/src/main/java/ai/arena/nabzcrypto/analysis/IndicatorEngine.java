package ai.arena.nabzcrypto.analysis;

import ai.arena.nabzcrypto.model.AiAssessment;
import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketRegime;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.OrderBookMetrics;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;
import ai.arena.nabzcrypto.model.SignalDiagnostics;
import ai.arena.nabzcrypto.model.TradeChartData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Hybrid technical-analysis engine. It consumes completed candles only and gives a bounded vote
 * to a locally trained classifier; neither score is represented as a promise of future returns.
 */
public final class IndicatorEngine {
    private static final int MIN_FIVE_MINUTE_CANDLES = 55;
    private static final int MIN_FIFTEEN_MINUTE_CANDLES = 55;

    private IndicatorEngine() {}

    public static ScanResult analyze(MarketTicker ticker, List<Candle> fiveMinuteInput,
                                     List<Candle> fifteenMinuteInput, String source) {
        return analyze(ticker, fiveMinuteInput, fifteenMinuteInput,
                new ArrayList<>(), new ArrayList<>(), 0,
                OrderBookMetrics.unavailable(), 0.05, 1.0, source);
    }

    public static ScanResult analyze(MarketTicker ticker, List<Candle> fiveMinuteInput,
                                     List<Candle> fifteenMinuteInput,
                                     List<Candle> oneHourInput, List<Candle> fourHourInput,
                                     int benchmarkTrend, OrderBookMetrics orderBook,
                                     double feePerSidePct, double riskPerTradePct,
                                     String source) {
        if (ticker == null || !ticker.isValid()) {
            throw new IllegalArgumentException("Invalid ticker");
        }
        List<Candle> five = clean(fiveMinuteInput);
        List<Candle> fifteen = clean(fifteenMinuteInput);
        List<Candle> oneHour = clean(oneHourInput);
        List<Candle> fourHour = clean(fourHourInput);
        OrderBookMetrics book = orderBook == null
                ? OrderBookMetrics.unavailable() : orderBook;
        if (five.size() < MIN_FIVE_MINUTE_CANDLES) {
            throw new IllegalArgumentException("Not enough 5m candles");
        }
        if (fifteen.size() < MIN_FIFTEEN_MINUTE_CANDLES) {
            throw new IllegalArgumentException("Not enough 15m candles");
        }

        double[] close5 = closes(five);
        double[] close15 = closes(fifteen);
        double[] ema9Series = emaSeries(close15, 9);
        double[] ema21Series = emaSeries(close15, 21);
        double[] ema50Series = emaSeries(close15, 50);
        double[] ema5FastSeries = emaSeries(close5, 9);
        double[] ema5SlowSeries = emaSeries(close5, 21);
        int last15 = close15.length - 1;
        int last5 = close5.length - 1;

        double reference = close5[last5];
        double ema9 = ema9Series[last15];
        double ema21 = ema21Series[last15];
        double ema50 = ema50Series[last15];
        double rsi = rsi(close15, 14);
        double[] histogram = macdHistogram(close15);
        double macd = histogram[last15];
        double previousMacd = histogram[Math.max(0, last15 - 1)];
        double atr = atr(fifteen, 14);
        if (!finitePositive(atr)) atr = reference * 0.01;
        DirectionalStrength directional = directionalStrength(fifteen, 14);
        MarketRegime regime = marketRegime(directional, atr / reference * 100.0);

        double return5 = percent(reference, close5[last5 - 1]);
        double return15 = percent(reference, close5[last5 - 3]);
        double return1h = percent(reference, close5[last5 - 12]);
        double volumeRatio = volumeRatio(five, 1);
        double threeBarVolumeRatio = volumeRatio(five, 3);
        double effectiveVolumeRatio = Math.max(volumeRatio, threeBarVolumeRatio);
        double movementZ = movementZScore(close5);
        boolean breakoutUp = reference > previousHigh(five, 20);
        boolean breakoutDown = reference < previousLow(five, 20);

        boolean pump = (((return5 >= 1.0 && movementZ >= 3.2)
                || (return15 >= 2.8 && threeBarVolumeRatio >= 1.8))
                && effectiveVolumeRatio >= 1.8 && (breakoutUp || rsi >= 70));
        boolean dump = (((return5 <= -1.0 && movementZ <= -3.2)
                || (return15 <= -2.8 && threeBarVolumeRatio >= 1.8))
                && effectiveVolumeRatio >= 1.8 && (breakoutDown || rsi <= 30));

        int score = 0;
        score += relativeVote(ema9, ema21, 14, 0.03);
        score += relativeVote(ema21, ema50, 16, 0.05);
        score += relativeVote(reference, ema21, 10, 0.03);
        double ema21Past = ema21Series[Math.max(0, last15 - 4)];
        score += relativeVote(ema21, ema21Past, 8, 0.03);
        score += relativeVote(ema5FastSeries[last5], ema5SlowSeries[last5], 6, 0.025);
        if (directional.adx >= 20 && Math.abs(directional.plusDi - directional.minusDi) >= 3) {
            int adxVote = directional.adx >= 28 ? 8 : directional.adx >= 23 ? 6 : 4;
            score += directional.plusDi > directional.minusDi ? adxVote : -adxVote;
        }
        double macdNoise = Math.max(reference * 0.00005, 1e-12);
        if (macd > macdNoise) score += 12;
        else if (macd < -macdNoise) score -= 12;
        double macdDelta = macd - previousMacd;
        double deltaNoise = Math.max(reference * 0.00001, 1e-12);
        if (macdDelta > deltaNoise) score += 5;
        else if (macdDelta < -deltaNoise) score -= 5;

        if (rsi >= 55 && rsi <= 68) score += 11;
        else if (rsi >= 52 && rsi < 55) score += 5;
        else if (rsi >= 45 && rsi <= 48) score -= 4;
        else if (rsi >= 30 && rsi < 45) score -= 10;
        else if (rsi > 75) score -= 9;
        // Oversold is an anti-chase vote, not another reason to short at the low.
        else if (rsi < 25) score += 9;

        score += (int) Math.round(clamp(return15 * 3.0, -12, 12));
        score += (int) Math.round(clamp(return1h * 1.4, -10, 10));
        if (effectiveVolumeRatio >= 1.4) {
            int volumeVote = (int) Math.round(clamp((effectiveVolumeRatio - 1.0) * 5.0, 3, 9));
            if (return15 > 0.15) score += volumeVote;
            else if (return15 < -0.15) score -= volumeVote;
        }
        if (regime == MarketRegime.RANGE) score = (int) Math.round(score * 0.82);
        else if (regime == MarketRegime.VOLATILE) score = (int) Math.round(score * 0.90);
        int technicalScore = (int) clamp(score, -100, 100);
        int oneHourTrend = timeframeTrend(oneHour);
        int fourHourTrend = timeframeTrend(fourHour);
        int contextVote = contextVote(oneHourTrend, fourHourTrend, benchmarkTrend);
        AiAssessment ai;
        try {
            ai = OnDeviceAiAnalyzer.analyze(five);
        } catch (RuntimeException ignored) {
            // AI is supplementary; a numerical/model failure must never hide technical analysis.
            ai = AiAssessment.unavailable();
        }
        score = (int) clamp(technicalScore + ai.vote + contextVote, -100, 100);
        long candleClosedAt = five.get(five.size() - 1).closeTime;
        long age = Math.max(0, System.currentTimeMillis() - candleClosedAt);
        int dataQuality = dataQuality(ticker, five.size(), age);

        boolean bearishPlan = score < 0;
        double zoneLow = bearishPlan ? Math.max(0, reference - atr * 0.15)
                : Math.max(0, reference - atr * 0.25);
        double zoneHigh = bearishPlan ? reference + atr * 0.25 : reference + atr * 0.15;
        double invalidation = bearishPlan ? reference + atr : Math.max(0, reference - atr);
        double objective1 = bearishPlan ? Math.max(0, reference - 1.5 * atr)
                : reference + 1.5 * atr;
        double objective2 = bearishPlan ? Math.max(0, reference - 2.5 * atr)
                : reference + 2.5 * atr;
        // R:R and journal outcomes are anchored to the exact issuance price. The displayed
        // zone is contextual, not an assumption of a better fill.
        double risk = Math.abs(reference - invalidation);
        double reward1 = Math.abs(objective1 - reference);
        double reward2 = Math.abs(objective2 - reference);
        double rawRiskReward1 = risk > 0 ? reward1 / risk : 0;
        double rawRiskReward2 = risk > 0 ? reward2 / risk : 0;
        double fee = Double.isFinite(feePerSidePct) ? clamp(feePerSidePct, 0, 1) : 0.10;
        double slippageRoundTrip = book.available
                ? Math.min(2.0, book.estimatedSlippagePct * 2.0) : 0.10;
        double roundTripCost = fee * 2.0 + slippageRoundTrip;
        double riskPct = risk / Math.max(1e-12, reference) * 100.0;
        double reward1Pct = reward1 / Math.max(1e-12, reference) * 100.0;
        double reward2Pct = reward2 / Math.max(1e-12, reference) * 100.0;
        double netRisk = Math.max(1e-9, riskPct + roundTripCost);
        double netRiskReward2 = (reward2Pct - roundTripCost) / netRisk;
        double blendedRewardPct = (reward1Pct + reward2Pct) / 2.0;
        double netBlendedRiskReward = (blendedRewardPct - roundTripCost) / netRisk;
        // The journal scales out half at each target, so the cost gate evaluates that full
        // lifecycle rather than demanding TP1 alone pay for the entire remaining position.
        boolean riskRewardPassed = rawRiskReward1 >= 1.45 && rawRiskReward2 >= 2.30
                && netRiskReward2 >= 1.20 && netBlendedRiskReward >= 0.85;
        boolean liquidityPassed = !book.available || (book.spreadPct <= 0.80
                && book.minimumDepthUsd() >= 10_000 && book.estimatedSlippagePct <= 0.75);
        boolean liquidityStrong = book.available && book.spreadPct <= 0.35
                && book.minimumDepthUsd() >= 50_000 && book.estimatedSlippagePct <= 0.30;
        int intendedDirection = score > 0 ? 1 : score < 0 ? -1 : 0;
        double extensionAtr = (reference - ema21) / Math.max(1e-12, atr);
        boolean chasingBuy = intendedDirection > 0 && ((rsi >= 63 && return15 > 0)
                || extensionAtr >= 1.20 || return15 >= 1.80);
        boolean chasingSell = intendedDirection < 0 && ((rsi <= 37 && return15 < 0)
                || extensionAtr <= -1.20 || return15 <= -1.80);
        boolean entryTimingPassed = !chasingBuy && !chasingSell;
        boolean higherAligned = higherTimeframeAligned(intendedDirection,
                oneHourTrend, fourHourTrend, benchmarkTrend,
                oneHour.size() >= 40, fourHour.size() >= 40);
        boolean independentConfirmation = intendedDirection > 0
                ? (ai.reliable && ai.vote > 0 && ai.confidence >= 25)
                    || (regime == MarketRegime.TREND_UP && directional.adx >= 25)
                : intendedDirection < 0
                    && ((ai.reliable && ai.vote < 0 && ai.confidence >= 25)
                    || (regime == MarketRegime.TREND_DOWN && directional.adx >= 25));
        boolean qualityStrong = dataQuality >= 75;
        boolean technicalStrong = Math.abs(technicalScore) >= 48;
        boolean regimeStrong = regime != MarketRegime.RANGE && regime != MarketRegime.VOLATILE;
        boolean strongEligible = qualityStrong && technicalStrong && regimeStrong
                && independentConfirmation && higherAligned && liquidityStrong
                && riskRewardPassed && entryTimingPassed && !ai.driftDetected;
        int historyDays = historyDays(five);
        double riskSetting = Double.isFinite(riskPerTradePct)
                ? clamp(riskPerTradePct, 0.1, 5.0) : 1.0;
        double totalLossPct = riskPct + roundTripCost;
        double positionPer1000 = totalLossPct > 0
                ? Math.min(1_000, 1_000 * riskSetting / totalLossPct) : 0;
        SignalDiagnostics diagnostics = new SignalDiagnostics(oneHourTrend, fourHourTrend,
                benchmarkTrend, higherAligned, book, rawRiskReward1, rawRiskReward2,
                roundTripCost, positionPer1000, riskRewardPassed, liquidityPassed,
                historyDays, qualityStrong, technicalStrong, regimeStrong,
                independentConfirmation, entryTimingPassed, strongEligible);

        Signal signal = classifySignal(score, technicalScore, ai, regime, directional,
                dataQuality, diagnostics, pump, dump);

        List<String> reasons = buildReasons(signal, score, ema9, ema21, ema50, rsi,
                macd, return5, return15, effectiveVolumeRatio, movementZ);
        reasons.add(String.format(Locale.US, "رژیم بازار: %s • ADX %.1f • کیفیت داده %d/100",
                regime.faLabel, directional.adx, dataQuality));
        reasons.add(String.format(Locale.US,
                "روند ۱ساعت %+d • ۴ساعت %+d • زمینهٔ BTC/ETH %+d",
                oneHourTrend, fourHourTrend, benchmarkTrend));
        reasons.add(String.format(Locale.US,
                "نسبت سود‌به‌ریسک هدف ۱/۲: %.2f / %.2f • هزینهٔ برآوردی %.2f%%",
                rawRiskReward1, rawRiskReward2, roundTripCost));
        reasons.add(ai.shortFa());
        List<String> cautions = buildCautions(ticker, signal, rsi, effectiveVolumeRatio,
                reference, atr, candleClosedAt);
        if (ai.reliable && technicalScore != 0 && ai.vote != 0
                && Integer.signum(technicalScore) != Integer.signum(ai.vote)) {
            cautions.add("رأی ensemble هوش مصنوعی با جهت تکنیکال مخالف است؛ سیگنال جهت‌دار مسدود یا ضعیف شده است.");
        }
        if (regime == MarketRegime.RANGE) {
            cautions.add("ADX بازار را بی‌روند نشان می‌دهد؛ آستانهٔ سیگنال جهت‌دار سخت‌گیرانه‌تر شده است.");
        } else if (regime == MarketRegime.VOLATILE) {
            cautions.add("ATR نوسان بسیار بالا را نشان می‌دهد؛ سیگنال قوی تا بازگشت ثبات محدود شده است.");
        }
        if (dataQuality < 75) {
            cautions.add("کیفیت داده برای سیگنال قوی کافی نیست؛ تعداد منبع، spread، حجم و تازگی بررسی شوند.");
        }
        if (!higherAligned && intendedDirection != 0) {
            cautions.add("تایم‌فریم ۱ساعته/۴ساعته یا زمینهٔ BTC/ETH جهت فعلی را کامل تأیید نمی‌کند.");
        }
        if (!riskRewardPassed) {
            cautions.add("پس از هزینه و لغزش، نسبت سود‌به‌ریسک برای ورود جهت‌دار کافی نیست.");
        }
        if (!liquidityPassed) {
            cautions.add("عمق دفتر سفارش یا spread برای ورود قابل‌اتکا کافی نیست؛ سیگنال جهت‌دار مسدود شد.");
        } else if (!book.available) {
            cautions.add("دفتر سفارش در این اسکن در دسترس نبود؛ برچسب قوی صادر نمی‌شود.");
        }
        if (!entryTimingPassed) {
            cautions.add("قیمت از EMA/ATR بیش از حد دور یا RSI کشیده است؛ برای جلوگیری از تعقیب حرکت، ورود به «صبر» تبدیل شد.");
        }
        if (ai.driftDetected) {
            cautions.add("دقت پنجرهٔ اخیر AI افت کرده است؛ رأی مدل تا بازیابی کیفیت غیرفعال شد.");
        }
        if (signal == Signal.NEUTRAL && Math.abs(score) >= 36) {
            cautions.add("با وجود امتیاز جهت‌دار، گیت AI/رژیم/کیفیت تأیید کامل نداد؛ خروجی عمداً «صبر» است.");
        }

        if (age > 30L * 60L * 1000L) {
            signal = Signal.STALE;
            cautions.add("آخرین کندل بسته‌شده قدیمی است؛ تا به‌روزرسانی بعدی تصمیم نگیرید.");
        }

        int evidence = evidenceStrength(signal, score, reference, ema9, ema21, ema50,
                macd, rsi, return15, effectiveVolumeRatio, movementZ);
        evidence = (int) Math.round(evidence * (0.70 + dataQuality / 100.0 * 0.30));
        int signalDirection = signal.isBuy() ? 1 : signal.isSell() ? -1 : 0;
        if (signalDirection != 0 && ai.vote != 0) {
            evidence += Integer.signum(ai.vote) == signalDirection ? 4 : -8;
        }
        evidence = (int) clamp(evidence, 25, 95);

        return new ScanResult(ticker, signal, score, technicalScore, evidence, dataQuality,
                ai, regime, directional.adx, rsi, ema9, ema21, ema50,
                macd, atr, return5, return15, return1h, effectiveVolumeRatio, movementZ,
                reference, zoneLow, zoneHigh, invalidation, objective1, objective2,
                candleClosedAt, source, reasons, cautions,
                tail(close5, 32), false, diagnostics,
                TradeChartData.fromCandles(five, 120));
    }

    private static Signal classifySignal(int score, int technicalScore, AiAssessment ai,
                                         MarketRegime regime, DirectionalStrength directional,
                                         int dataQuality, SignalDiagnostics diagnostics,
                                         boolean pump, boolean dump) {
        if (pump) return Signal.PUMP_ALERT;
        if (dump) return Signal.DUMP_ALERT;
        if (dataQuality < 60 || !diagnostics.riskRewardPassed
                || !diagnostics.liquidityPassed || !diagnostics.entryTimingPassed) {
            return Signal.NEUTRAL;
        }

        boolean aiBlocksBuy = ai.reliable && ai.vote < 0 && ai.confidence >= 18;
        boolean aiBlocksSell = ai.reliable && ai.vote > 0 && ai.confidence >= 18;
        boolean regimeBlocksBuy = regime == MarketRegime.TREND_DOWN && directional.adx >= 22;
        boolean regimeBlocksSell = regime == MarketRegime.TREND_UP && directional.adx >= 22;
        boolean higherBlocksBuy = diagnostics.oneHourTrend <= -50
                || diagnostics.fourHourTrend <= -35 || diagnostics.benchmarkTrend <= -50;
        boolean higherBlocksSell = diagnostics.oneHourTrend >= 50
                || diagnostics.fourHourTrend >= 35 || diagnostics.benchmarkTrend >= 50;
        boolean bookBlocksBuy = diagnostics.orderBook.available
                && diagnostics.orderBook.imbalance <= -0.65;
        boolean bookBlocksSell = diagnostics.orderBook.available
                && diagnostics.orderBook.imbalance >= 0.65;
        int threshold = regime == MarketRegime.RANGE || regime == MarketRegime.VOLATILE ? 44 : 36;

        if (score >= 68 && technicalScore >= 48 && diagnostics.strongEligible
                && !aiBlocksBuy && !regimeBlocksBuy && !higherBlocksBuy && !bookBlocksBuy) {
            return Signal.STRONG_BUY;
        }
        if (score <= -68 && technicalScore <= -48 && diagnostics.strongEligible
                && !aiBlocksSell && !regimeBlocksSell && !higherBlocksSell && !bookBlocksSell) {
            return Signal.STRONG_SELL;
        }
        if (score >= threshold && technicalScore >= 28 && !aiBlocksBuy
                && !regimeBlocksBuy && !higherBlocksBuy && !bookBlocksBuy) {
            return Signal.BUY;
        }
        if (score <= -threshold && technicalScore <= -28 && !aiBlocksSell
                && !regimeBlocksSell && !higherBlocksSell && !bookBlocksSell) {
            return Signal.SELL;
        }
        return Signal.NEUTRAL;
    }

    public static int timeframeTrend(List<Candle> input) {
        List<Candle> candles = clean(input);
        if (candles.size() < 40) return 0;
        double[] close = closes(candles);
        int last = close.length - 1;
        double[] fast = emaSeries(close, 9);
        double[] medium = emaSeries(close, 21);
        double[] slow = emaSeries(close, 50);
        int score = 0;
        score += relativeVote(fast[last], medium[last], 30, 0.04);
        score += relativeVote(medium[last], slow[last], 25, 0.06);
        score += relativeVote(close[last], medium[last], 15, 0.04);
        score += relativeVote(medium[last], medium[Math.max(0, last - 4)], 15, 0.05);
        double momentum = percent(close[last], close[Math.max(0, last - 6)]);
        score += (int) Math.round(clamp(momentum * 4.0, -10, 10));
        double valueRsi = rsi(close, 14);
        if (valueRsi >= 55 && valueRsi <= 72) score += 8;
        else if (valueRsi <= 45 && valueRsi >= 28) score -= 8;
        return (int) clamp(score, -100, 100);
    }

    private static int contextVote(int oneHourTrend, int fourHourTrend, int benchmarkTrend) {
        int vote = 0;
        if (oneHourTrend >= 20) vote += 3;
        else if (oneHourTrend <= -20) vote -= 3;
        if (fourHourTrend >= 20) vote += 4;
        else if (fourHourTrend <= -20) vote -= 4;
        if (benchmarkTrend >= 25) vote += 3;
        else if (benchmarkTrend <= -25) vote -= 3;
        return (int) clamp(vote, -10, 10);
    }

    private static boolean higherTimeframeAligned(int direction, int oneHourTrend,
                                                  int fourHourTrend, int benchmarkTrend,
                                                  boolean oneHourAvailable,
                                                  boolean fourHourAvailable) {
        if (direction == 0 || !oneHourAvailable || !fourHourAvailable) return false;
        if (direction > 0) {
            return oneHourTrend >= 5 && fourHourTrend >= 0 && benchmarkTrend >= -20;
        }
        return oneHourTrend <= -5 && fourHourTrend <= 0 && benchmarkTrend <= 20;
    }

    private static int historyDays(List<Candle> candles) {
        if (candles == null || candles.size() < 2) return 0;
        long span = Math.max(0, candles.get(candles.size() - 1).closeTime
                - candles.get(0).openTime);
        long day = 24L * 60L * 60L * 1000L;
        int spanDays = (int) Math.max(1, (span + day / 2L) / day);
        int coverageDays = candles.size() / (24 * 12);
        return Math.min(30, Math.min(spanDays, coverageDays));
    }

    private static int dataQuality(MarketTicker ticker, int candleCount, long ageMs) {
        int quality = 100;
        if (ticker.sourceCount <= 1) quality -= 12;
        else if (ticker.sourceCount == 2) quality -= 5;
        if (ticker.priceSpreadPct > 0.25) {
            quality -= (int) Math.round(Math.min(30, (ticker.priceSpreadPct - 0.25) * 8.0));
        }
        if (ticker.quoteVolume24h < 500_000) quality -= 18;
        else if (ticker.quoteVolume24h < 2_000_000) quality -= 10;
        else if (ticker.quoteVolume24h < 5_000_000) quality -= 4;
        if (candleCount < 180) quality -= 10;
        else if (candleCount < 250) quality -= 5;
        if (ageMs > 20L * 60L * 1000L) quality -= 22;
        else if (ageMs > 12L * 60L * 1000L) quality -= 10;
        return (int) clamp(quality, 0, 100);
    }

    private static MarketRegime marketRegime(DirectionalStrength directional, double atrPercent) {
        if (atrPercent >= 3.5) return MarketRegime.VOLATILE;
        if (directional.adx >= 22 && directional.plusDi > directional.minusDi + 2) {
            return MarketRegime.TREND_UP;
        }
        if (directional.adx >= 22 && directional.minusDi > directional.plusDi + 2) {
            return MarketRegime.TREND_DOWN;
        }
        if (directional.adx < 17) return MarketRegime.RANGE;
        return MarketRegime.TRANSITION;
    }

    private static DirectionalStrength directionalStrength(List<Candle> candles, int period) {
        if (candles.size() < period * 2 + 1) return new DirectionalStrength(0, 0, 0);
        int last = candles.size() - 1;
        double dxSum = 0;
        int dxCount = 0;
        DirectionalStrength latest = null;
        for (int end = Math.max(period, last - period + 1); end <= last; end++) {
            double trueRange = 0;
            double plusDm = 0;
            double minusDm = 0;
            int start = Math.max(1, end - period + 1);
            for (int i = start; i <= end; i++) {
                Candle current = candles.get(i);
                Candle previous = candles.get(i - 1);
                trueRange += Math.max(current.high - current.low,
                        Math.max(Math.abs(current.high - previous.close),
                                Math.abs(current.low - previous.close)));
                double up = current.high - previous.high;
                double down = previous.low - current.low;
                if (up > down && up > 0) plusDm += up;
                if (down > up && down > 0) minusDm += down;
            }
            double plusDi = trueRange > 0 ? plusDm / trueRange * 100.0 : 0;
            double minusDi = trueRange > 0 ? minusDm / trueRange * 100.0 : 0;
            double denominator = plusDi + minusDi;
            double dx = denominator > 0 ? Math.abs(plusDi - minusDi) / denominator * 100.0 : 0;
            dxSum += dx;
            dxCount++;
            latest = new DirectionalStrength(0, plusDi, minusDi);
        }
        return latest == null ? new DirectionalStrength(0, 0, 0)
                : new DirectionalStrength(dxSum / Math.max(1, dxCount),
                        latest.plusDi, latest.minusDi);
    }

    /** Builds closed 15-minute candles when a provider's 15m endpoint is temporarily unavailable. */
    public static List<Candle> aggregateToFifteenMinutes(List<Candle> fiveMinuteInput) {
        return aggregateCandles(fiveMinuteInput, 5, 15);
    }

    public static List<Candle> aggregateCandles(List<Candle> input, int sourceMinutes,
                                                int targetMinutes) {
        List<Candle> source = clean(input);
        List<Candle> result = new ArrayList<>();
        if (sourceMinutes <= 0 || targetMinutes <= sourceMinutes
                || targetMinutes % sourceMinutes != 0) return result;
        int required = targetMinutes / sourceMinutes;
        final long width = targetMinutes * 60_000L;
        int index = 0;
        while (index < source.size()) {
            long bucket = (source.get(index).openTime / width) * width;
            int end = index;
            while (end < source.size() && (source.get(end).openTime / width) * width == bucket) end++;
            if (end - index >= required) {
                Candle first = source.get(index);
                Candle last = source.get(end - 1);
                double high = -Double.MAX_VALUE;
                double low = Double.MAX_VALUE;
                double baseVolume = 0;
                double quoteVolume = 0;
                for (int i = index; i < end; i++) {
                    Candle candle = source.get(i);
                    high = Math.max(high, candle.high);
                    low = Math.min(low, candle.low);
                    baseVolume += candle.baseVolume;
                    quoteVolume += candle.quoteVolume;
                }
                result.add(new Candle(bucket, bucket + width - 1, first.open, high, low,
                        last.close, baseVolume, quoteVolume));
            }
            index = end;
        }
        return result;
    }

    public static double[] emaSeries(double[] values, int period) {
        if (values == null || values.length == 0 || period <= 0) return new double[0];
        double[] result = new double[values.length];
        double alpha = 2.0 / (period + 1.0);
        result[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            result[i] = alpha * values[i] + (1.0 - alpha) * result[i - 1];
        }
        return result;
    }

    public static double rsi(double[] closes, int period) {
        if (closes == null || closes.length <= period || period <= 0) return 50.0;
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= period; i++) {
            double delta = closes[i] - closes[i - 1];
            if (delta > 0) gain += delta;
            else loss -= delta;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        for (int i = period + 1; i < closes.length; i++) {
            double delta = closes[i] - closes[i - 1];
            avgGain = (avgGain * (period - 1) + Math.max(delta, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-delta, 0)) / period;
        }
        if (avgLoss == 0 && avgGain == 0) return 50.0;
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    private static double[] macdHistogram(double[] closes) {
        double[] fast = emaSeries(closes, 12);
        double[] slow = emaSeries(closes, 26);
        double[] macd = new double[closes.length];
        for (int i = 0; i < closes.length; i++) macd[i] = fast[i] - slow[i];
        double[] signal = emaSeries(macd, 9);
        double[] histogram = new double[closes.length];
        for (int i = 0; i < closes.length; i++) histogram[i] = macd[i] - signal[i];
        return histogram;
    }

    private static double atr(List<Candle> candles, int period) {
        if (candles.size() <= period) return Double.NaN;
        double[] ranges = new double[candles.size() - 1];
        for (int i = 1; i < candles.size(); i++) {
            Candle current = candles.get(i);
            double previousClose = candles.get(i - 1).close;
            ranges[i - 1] = Math.max(current.high - current.low,
                    Math.max(Math.abs(current.high - previousClose),
                            Math.abs(current.low - previousClose)));
        }
        double value = 0;
        for (int i = 0; i < period; i++) value += ranges[i];
        value /= period;
        for (int i = period; i < ranges.length; i++) {
            value = (value * (period - 1) + ranges[i]) / period;
        }
        return value;
    }

    private static double volumeRatio(List<Candle> candles, int recentCount) {
        int n = candles.size();
        int recentStart = Math.max(0, n - recentCount);
        double recent = 0;
        for (int i = recentStart; i < n; i++) recent += effectiveVolume(candles.get(i));
        recent /= Math.max(1, n - recentStart);

        int historyEnd = recentStart;
        int historyStart = Math.max(0, historyEnd - 24);
        if (historyEnd - historyStart < 5) return 1.0;
        double[] history = new double[historyEnd - historyStart];
        for (int i = historyStart; i < historyEnd; i++) {
            history[i - historyStart] = effectiveVolume(candles.get(i));
        }
        double baseline = median(history);
        return baseline > 0 ? clamp(recent / baseline, 0, 99) : 1.0;
    }

    private static double effectiveVolume(Candle candle) {
        return candle.quoteVolume > 0 ? candle.quoteVolume : candle.baseVolume * candle.close;
    }

    private static double movementZScore(double[] closes) {
        int n = closes.length;
        double current = percent(closes[n - 1], closes[n - 2]);
        int start = Math.max(1, n - 50);
        int count = Math.max(0, (n - 1) - start);
        if (count < 10) return 0;
        double[] history = new double[count];
        for (int i = start; i < n - 1; i++) {
            history[i - start] = percent(closes[i], closes[i - 1]);
        }
        double center = median(history);
        double[] deviations = new double[history.length];
        for (int i = 0; i < history.length; i++) deviations[i] = Math.abs(history[i] - center);
        double robustScale = Math.max(0.04, 1.4826 * median(deviations));
        return clamp((current - center) / robustScale, -12, 12);
    }

    private static List<String> buildReasons(Signal signal, int score, double ema9, double ema21,
                                             double ema50, double rsi, double macd,
                                             double return5, double return15,
                                             double volumeRatio, double movementZ) {
        List<String> reasons = new ArrayList<>();
        if (signal == Signal.PUMP_ALERT) {
            reasons.add(String.format(Locale.US, "جهش ۵ دقیقه: %+.2f%% با ناهنجاری %.1fσ", return5, movementZ));
            reasons.add(String.format(Locale.US, "حجم اخیر %.1f برابر میانهٔ قبلی است", volumeRatio));
            reasons.add("حرکت سریع می‌تواند اصلاح ناگهانی داشته باشد؛ ورود هیجانی پرریسک است.");
            return reasons;
        }
        if (signal == Signal.DUMP_ALERT) {
            reasons.add(String.format(Locale.US, "ریزش ۵ دقیقه: %+.2f%% با ناهنجاری %.1fσ", return5, movementZ));
            reasons.add(String.format(Locale.US, "حجم اخیر %.1f برابر میانهٔ قبلی است", volumeRatio));
            reasons.add("فشار فروش غیرعادی است؛ قبل از هر اقدام منتظر تثبیت بمانید.");
            return reasons;
        }

        boolean bullish = score > 0;
        if (bullish && ema9 > ema21 && ema21 > ema50) {
            reasons.add("چیدمان EMAهای ۹، ۲۱ و ۵۰ در تایم ۱۵دقیقه صعودی است.");
        } else if (!bullish && ema9 < ema21 && ema21 < ema50) {
            reasons.add("چیدمان EMAهای ۹، ۲۱ و ۵۰ در تایم ۱۵دقیقه نزولی است.");
        } else {
            reasons.add("میانگین‌های متحرک هم‌جهت نیستند؛ بازار در فاز گذار است.");
        }
        reasons.add(macd >= 0 ? "مومنتوم MACD بالای خط مبناست."
                : "مومنتوم MACD زیر خط مبناست.");
        reasons.add(String.format(Locale.US, "RSI(14) برابر %.1f و بازده ۱۵دقیقه %+.2f%% است.", rsi, return15));
        if (volumeRatio >= 1.35) {
            reasons.add(String.format(Locale.US, "حجم %.1f برابر میانه و تأییدکنندهٔ حرکت است.", volumeRatio));
        } else {
            reasons.add("حجم هنوز تأیید قدرتمندی برای ادامهٔ حرکت نمی‌دهد.");
        }
        return reasons;
    }

    private static List<String> buildCautions(MarketTicker ticker, Signal signal, double rsi,
                                              double volumeRatio, double reference, double atr,
                                              long candleClosedAt) {
        List<String> cautions = new ArrayList<>();
        if (rsi >= 75) cautions.add("RSI در ناحیهٔ داغ است؛ ریسک خرید در سقف افزایش یافته.");
        if (rsi <= 25) cautions.add("RSI بسیار پایین است، اما اشباع فروش به‌تنهایی علامت برگشت نیست.");
        if (volumeRatio < 0.65) cautions.add("حجم اخیر کم است و اعتبار شکست قیمت پایین‌تر می‌آید.");
        if (Math.abs(ticker.change24hPct) >= 18) cautions.add("نوسان ۲۴ساعته بسیار بالاست؛ اندازهٔ موقعیت را کاهش دهید.");
        if (ticker.sourceCount <= 1) {
            cautions.add("این نماد فعلاً فقط از یک صرافی قیمت معتبر دارد؛ اجماع بین‌منبعی ممکن نیست.");
        } else if (ticker.priceSpreadPct >= 1.5) {
            cautions.add(String.format(Locale.US,
                    "اختلاف قیمت میان منابع %.2f%% است؛ پیش از اقدام دفتر سفارش را بررسی کنید.",
                    ticker.priceSpreadPct));
        }
        double priceGap = Math.abs(ticker.lastPrice - reference) / reference * 100.0;
        double atrPct = atr / reference * 100.0;
        if (priceGap > Math.max(3.0, atrPct * 2.5)) {
            cautions.add("قیمت لحظه‌ای از آخرین کندل بسته‌شده فاصله دارد؛ داده ممکن است در حال جهش باشد.");
        }
        if (signal.isMovementAlert()) cautions.add("هشدار جهش/ریزش اثبات دست‌کاری بازار نیست.");
        if (System.currentTimeMillis() - candleClosedAt > 18L * 60L * 1000L) {
            cautions.add("تازه‌بودن داده کمتر از حالت ایده‌آل است.");
        }
        return cautions;
    }

    private static int evidenceStrength(Signal signal, int score, double price,
                                        double ema9, double ema21, double ema50,
                                        double macd, double rsi, double return15,
                                        double volumeRatio, double movementZ) {
        if (signal.isMovementAlert()) {
            return (int) clamp(55 + Math.abs(movementZ) * 5 + Math.min(15, volumeRatio * 3), 55, 95);
        }
        int direction = Integer.compare(score, 0);
        if (direction == 0) return 38;
        int agree = 0;
        if (Integer.compare(Double.compare(ema9, ema21), 0) == direction) agree++;
        if (Integer.compare(Double.compare(ema21, ema50), 0) == direction) agree++;
        if (Integer.compare(Double.compare(price, ema21), 0) == direction) agree++;
        if (Integer.compare(Double.compare(macd, 0), 0) == direction) agree++;
        if (Integer.compare(Double.compare(return15, 0), 0) == direction) agree++;
        if ((direction > 0 && rsi >= 50) || (direction < 0 && rsi < 50)) agree++;
        if (volumeRatio >= 1.2) agree++;
        return (int) clamp(35 + agree * 7 + Math.abs(score) / 6.0, 35, 95);
    }

    private static List<Candle> clean(List<Candle> input) {
        List<Candle> result = new ArrayList<>();
        if (input == null) return result;
        long completedBefore = System.currentTimeMillis() - 500L;
        for (Candle candle : input) {
            if (candle != null && candle.isValid() && candle.closeTime < completedBefore) {
                result.add(candle);
            }
        }
        result.sort(Comparator.comparingLong(c -> c.openTime));
        List<Candle> unique = new ArrayList<>();
        long previous = Long.MIN_VALUE;
        for (Candle candle : result) {
            if (candle.openTime != previous) {
                unique.add(candle);
                previous = candle.openTime;
            } else {
                unique.set(unique.size() - 1, candle);
            }
        }
        return unique;
    }

    private static double[] closes(List<Candle> candles) {
        double[] result = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) result[i] = candles.get(i).close;
        return result;
    }

    private static double previousHigh(List<Candle> candles, int lookback) {
        double high = -Double.MAX_VALUE;
        int end = candles.size() - 1;
        for (int i = Math.max(0, end - lookback); i < end; i++) high = Math.max(high, candles.get(i).high);
        return high;
    }

    private static double previousLow(List<Candle> candles, int lookback) {
        double low = Double.MAX_VALUE;
        int end = candles.size() - 1;
        for (int i = Math.max(0, end - lookback); i < end; i++) low = Math.min(low, candles.get(i).low);
        return low;
    }

    private static double percent(double current, double previous) {
        return finitePositive(previous) && Double.isFinite(current)
                ? (current / previous - 1.0) * 100.0 : 0;
    }

    private static double median(double[] values) {
        if (values.length == 0) return 0;
        double[] copy = values.clone();
        Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2.0 : copy[middle];
    }

    private static double[] tail(double[] values, int count) {
        int start = Math.max(0, values.length - count);
        return Arrays.copyOfRange(values, start, values.length);
    }

    private static int relativeVote(double current, double baseline, int weight,
                                    double tolerancePercent) {
        double tolerance = Math.max(Math.abs(baseline) * tolerancePercent / 100.0, 1e-12);
        if (current - baseline > tolerance) return weight;
        if (baseline - current > tolerance) return -weight;
        return 0;
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class DirectionalStrength {
        final double adx;
        final double plusDi;
        final double minusDi;

        DirectionalStrength(double adx, double plusDi, double minusDi) {
            this.adx = Double.isFinite(adx) ? clamp(adx, 0, 100) : 0;
            this.plusDi = Double.isFinite(plusDi) ? clamp(plusDi, 0, 100) : 0;
            this.minusDi = Double.isFinite(minusDi) ? clamp(minusDi, 0, 100) : 0;
        }
    }
}
