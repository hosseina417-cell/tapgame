package ai.arena.nabzcrypto.analysis;

import ai.arena.nabzcrypto.model.AiAssessment;
import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;

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
        if (ticker == null || !ticker.isValid()) {
            throw new IllegalArgumentException("Invalid ticker");
        }
        List<Candle> five = clean(fiveMinuteInput);
        List<Candle> fifteen = clean(fifteenMinuteInput);
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
        else if (rsi < 25) score -= 7;

        score += (int) Math.round(clamp(return15 * 3.0, -12, 12));
        score += (int) Math.round(clamp(return1h * 1.4, -10, 10));
        if (effectiveVolumeRatio >= 1.4) {
            int volumeVote = (int) Math.round(clamp((effectiveVolumeRatio - 1.0) * 5.0, 3, 9));
            if (return15 > 0.15) score += volumeVote;
            else if (return15 < -0.15) score -= volumeVote;
        }
        int technicalScore = (int) clamp(score, -100, 100);
        AiAssessment ai;
        try {
            ai = OnDeviceAiAnalyzer.analyze(five);
        } catch (RuntimeException ignored) {
            // AI is supplementary; a numerical/model failure must never hide technical analysis.
            ai = AiAssessment.unavailable();
        }
        score = (int) clamp(technicalScore + ai.vote, -100, 100);

        Signal signal;
        if (pump) signal = Signal.PUMP_ALERT;
        else if (dump) signal = Signal.DUMP_ALERT;
        else if (score >= 62) signal = Signal.STRONG_BUY;
        else if (score >= 32) signal = Signal.BUY;
        else if (score <= -62) signal = Signal.STRONG_SELL;
        else if (score <= -32) signal = Signal.SELL;
        else signal = Signal.NEUTRAL;

        List<String> reasons = buildReasons(signal, score, ema9, ema21, ema50, rsi,
                macd, return5, return15, effectiveVolumeRatio, movementZ);
        reasons.add(ai.shortFa());
        List<String> cautions = buildCautions(ticker, signal, rsi, effectiveVolumeRatio,
                reference, atr, five.get(five.size() - 1).closeTime);
        if (ai.reliable && technicalScore != 0 && ai.vote != 0
                && Integer.signum(technicalScore) != Integer.signum(ai.vote)) {
            cautions.add("رأی مدل AI با جهت اندیکاتورهای تکنیکال مخالف است؛ عدم‌قطعیت تحلیل بالاتر است.");
        }

        long age = System.currentTimeMillis() - five.get(five.size() - 1).closeTime;
        if (age > 30L * 60L * 1000L) {
            signal = Signal.STALE;
            cautions.add("آخرین کندل بسته‌شده قدیمی است؛ تا به‌روزرسانی بعدی تصمیم نگیرید.");
        }

        int evidence = evidenceStrength(signal, score, reference, ema9, ema21, ema50,
                macd, rsi, return15, effectiveVolumeRatio, movementZ);
        double zoneLow = Math.max(0, reference - atr * 0.25);
        double zoneHigh = reference + atr * 0.15;
        boolean bearish = signal.isSell() || signal == Signal.DUMP_ALERT || score < 0;
        double invalidation = bearish ? reference + 1.5 * atr : Math.max(0, reference - 1.5 * atr);
        double objective1 = bearish ? Math.max(0, reference - 1.5 * atr) : reference + 1.5 * atr;
        double objective2 = bearish ? Math.max(0, reference - 2.5 * atr) : reference + 2.5 * atr;

        return new ScanResult(ticker, signal, score, evidence, ai, rsi, ema9, ema21, ema50,
                macd, atr, return5, return15, return1h, effectiveVolumeRatio, movementZ,
                reference, zoneLow, zoneHigh, invalidation, objective1, objective2,
                five.get(five.size() - 1).closeTime, source, reasons, cautions,
                tail(close5, 32), false);
    }

    /** Builds closed 15-minute candles when a provider's 15m endpoint is temporarily unavailable. */
    public static List<Candle> aggregateToFifteenMinutes(List<Candle> fiveMinuteInput) {
        List<Candle> source = clean(fiveMinuteInput);
        List<Candle> result = new ArrayList<>();
        final long width = 15L * 60L * 1000L;
        int index = 0;
        while (index < source.size()) {
            long bucket = (source.get(index).openTime / width) * width;
            int end = index;
            while (end < source.size() && (source.get(end).openTime / width) * width == bucket) end++;
            if (end - index >= 3) {
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
}
