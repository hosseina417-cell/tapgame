package ai.arena.nabzcrypto.analysis;

import ai.arena.nabzcrypto.model.AiAssessment;
import ai.arena.nabzcrypto.model.Candle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Small supervised time-series classifier trained from scratch on each market's completed 5m
 * candles. Training and chronological holdout validation happen entirely on the phone. No model,
 * candle, symbol, or personal data is uploaded.
 */
public final class OnDeviceAiAnalyzer {
    private static final int LOOKBACK = 24;
    private static final int HORIZON = 3; // Predict the close three 5m candles ahead.
    private static final int FEATURE_COUNT = 8;
    private static final int MIN_TRAINING_ROWS = 70;
    private static final int MIN_VALIDATION_ROWS = 24;
    private static final int EPOCHS = 150;
    private static final double L2 = 0.018;

    private OnDeviceAiAnalyzer() {}

    public static AiAssessment analyze(List<Candle> input) {
        List<Candle> candles = clean(input);
        if (candles.size() < 125 || !contiguous(candles, candles.size() - 1)) {
            return AiAssessment.unavailable();
        }

        Dataset data = dataset(candles);
        if (data.rows.size() < MIN_TRAINING_ROWS + MIN_VALIDATION_ROWS + HORIZON) {
            return AiAssessment.unavailable();
        }
        int split = (int) Math.floor(data.rows.size() * 0.76);
        int trainEnd = split - HORIZON; // Purge labels overlapping the holdout boundary.
        int validationStart = split;
        int trainingCount = trainEnd;
        int validationCount = data.rows.size() - validationStart;
        if (trainingCount < MIN_TRAINING_ROWS || validationCount < MIN_VALIDATION_ROWS) {
            return AiAssessment.unavailable();
        }

        int trainPositive = positives(data.labels, 0, trainEnd);
        int validationPositive = positives(data.labels, validationStart, data.labels.size());
        int trainNegative = trainingCount - trainPositive;
        int validationNegative = validationCount - validationPositive;
        if (trainPositive < 10 || trainNegative < 10
                || validationPositive < 4 || validationNegative < 4) {
            return AiAssessment.unavailable();
        }

        Standardizer standardizer = fitStandardizer(data.rows, trainEnd);
        double[] weights = train(data, trainEnd, trainPositive, trainNegative, standardizer);
        Validation validation = validate(data, validationStart, weights, standardizer,
                validationPositive, validationNegative);

        double[] current = features(candles, candles.size() - 1);
        double probabilityUp = sigmoid(dot(weights, standardizer.transform(current)));
        boolean reliable = validation.balancedAccuracy >= 0.53 && validation.brier <= 0.255;
        double directionStrength = Math.abs(probabilityUp - 0.5) * 2.0;
        double quality = clamp((validation.balancedAccuracy - 0.5) / 0.20, 0, 1);
        double calibration = clamp((0.30 - validation.brier) / 0.15, 0, 1);
        int confidence = (int) Math.round(clamp(100.0 * directionStrength
                * (0.25 + 0.50 * quality + 0.25 * calibration), 0, 95));

        int vote = 0;
        if (reliable && directionStrength >= 0.12 && confidence >= 15) {
            double qualityFactor = 0.25 + 0.75 * quality;
            int magnitude = (int) Math.round(clamp(
                    (2 + directionStrength * 8 + quality * 2) * qualityFactor, 2, 12));
            vote = probabilityUp >= 0.5 ? magnitude : -magnitude;
        }
        return new AiAssessment(true, reliable, probabilityUp, confidence,
                validation.balancedAccuracy, validation.brier,
                trainingCount, validationCount, vote);
    }

    private static Dataset dataset(List<Candle> candles) {
        Dataset result = new Dataset();
        for (int t = LOOKBACK; t + HORIZON < candles.size(); t++) {
            if (!contiguous(candles, t + HORIZON)) continue;
            double[] row = features(candles, t);
            if (!allFinite(row)) continue;
            result.rows.add(row);
            result.labels.add(candles.get(t + HORIZON).close > candles.get(t).close ? 1 : 0);
        }
        return result;
    }

    private static double[] features(List<Candle> candles, int t) {
        double close = candles.get(t).close;
        double return1 = percent(close, candles.get(t - 1).close);
        double return3 = percent(close, candles.get(t - 3).close);
        double return12 = percent(close, candles.get(t - 12).close);
        double fast = meanClose(candles, t - 5, t);
        double slow = meanClose(candles, t - 17, t);
        double trendGap = percent(fast, slow);

        double[] recentReturns = new double[12];
        for (int i = 0; i < recentReturns.length; i++) {
            int index = t - recentReturns.length + 1 + i;
            recentReturns[i] = percent(candles.get(index).close, candles.get(index - 1).close);
        }
        double volatility = standardDeviation(recentReturns);

        double[] historyVolume = new double[20];
        for (int i = 0; i < historyVolume.length; i++) {
            historyVolume[i] = effectiveVolume(candles.get(t - historyVolume.length + i));
        }
        double volumeBaseline = median(historyVolume);
        double currentVolume = effectiveVolume(candles.get(t));
        double volumeLogRatio = volumeBaseline > 0
                ? Math.log(clamp(currentVolume / volumeBaseline, 0.05, 20.0)) : 0;

        Candle current = candles.get(t);
        double body = percent(current.close, current.open);
        double range = current.high - current.low;
        double rangePosition = range > 0
                ? clamp(((current.close - current.low) / range - 0.5) * 2.0, -1, 1) : 0;
        return new double[]{return1, return3, return12, trendGap,
                volatility, volumeLogRatio, body, rangePosition};
    }

    private static double[] train(Dataset data, int trainEnd, int positives, int negatives,
                                  Standardizer standardizer) {
        double[] weights = new double[FEATURE_COUNT + 1]; // Index zero is the intercept.
        double positiveWeight = trainEnd / (2.0 * positives);
        double negativeWeight = trainEnd / (2.0 * negatives);
        for (int epoch = 0; epoch < EPOCHS; epoch++) {
            double[] gradient = new double[weights.length];
            for (int i = 0; i < trainEnd; i++) {
                double[] x = standardizer.transform(data.rows.get(i));
                int y = data.labels.get(i);
                double sampleWeight = y == 1 ? positiveWeight : negativeWeight;
                double error = (sigmoid(dot(weights, x)) - y) * sampleWeight;
                gradient[0] += error;
                for (int j = 0; j < FEATURE_COUNT; j++) gradient[j + 1] += error * x[j];
            }
            double learningRate = 0.14 / Math.sqrt(1.0 + epoch * 0.035);
            weights[0] = clamp(weights[0] - learningRate * gradient[0] / trainEnd, -8, 8);
            for (int j = 1; j < weights.length; j++) {
                double regularized = gradient[j] / trainEnd + L2 * weights[j];
                weights[j] = clamp(weights[j] - learningRate * regularized, -8, 8);
            }
        }
        return weights;
    }

    private static Validation validate(Dataset data, int start, double[] weights,
                                       Standardizer standardizer, int positives, int negatives) {
        int truePositive = 0;
        int trueNegative = 0;
        double brier = 0;
        for (int i = start; i < data.rows.size(); i++) {
            int y = data.labels.get(i);
            double probability = sigmoid(dot(weights, standardizer.transform(data.rows.get(i))));
            if (probability >= 0.5 && y == 1) truePositive++;
            if (probability < 0.5 && y == 0) trueNegative++;
            double error = probability - y;
            brier += error * error;
        }
        int count = data.rows.size() - start;
        double sensitivity = positives > 0 ? truePositive / (double) positives : 0;
        double specificity = negatives > 0 ? trueNegative / (double) negatives : 0;
        return new Validation((sensitivity + specificity) / 2.0, brier / Math.max(1, count));
    }

    private static Standardizer fitStandardizer(List<double[]> rows, int end) {
        double[] mean = new double[FEATURE_COUNT];
        double[] scale = new double[FEATURE_COUNT];
        for (int i = 0; i < end; i++) {
            for (int j = 0; j < FEATURE_COUNT; j++) mean[j] += rows.get(i)[j];
        }
        for (int j = 0; j < FEATURE_COUNT; j++) mean[j] /= end;
        for (int i = 0; i < end; i++) {
            for (int j = 0; j < FEATURE_COUNT; j++) {
                double delta = rows.get(i)[j] - mean[j];
                scale[j] += delta * delta;
            }
        }
        for (int j = 0; j < FEATURE_COUNT; j++) {
            scale[j] = Math.sqrt(scale[j] / Math.max(1, end - 1));
            if (!Double.isFinite(scale[j]) || scale[j] < 1e-7) scale[j] = 1.0;
        }
        return new Standardizer(mean, scale);
    }

    private static boolean contiguous(List<Candle> candles, int end) {
        int start = Math.max(1, end - LOOKBACK);
        for (int i = start; i <= end; i++) {
            long gap = candles.get(i).openTime - candles.get(i - 1).openTime;
            if (gap < 60_000L || gap > 8L * 60_000L) return false;
        }
        return true;
    }

    private static List<Candle> clean(List<Candle> input) {
        List<Candle> sorted = new ArrayList<>();
        if (input == null) return sorted;
        long completedBefore = System.currentTimeMillis() - 500L;
        for (Candle candle : input) {
            if (candle != null && candle.isValid() && candle.closeTime < completedBefore) {
                sorted.add(candle);
            }
        }
        sorted.sort(Comparator.comparingLong(c -> c.openTime));
        List<Candle> unique = new ArrayList<>();
        for (Candle candle : sorted) {
            if (!unique.isEmpty() && unique.get(unique.size() - 1).openTime == candle.openTime) {
                unique.set(unique.size() - 1, candle);
            } else {
                unique.add(candle);
            }
        }
        return unique;
    }

    private static int positives(List<Integer> labels, int start, int end) {
        int result = 0;
        for (int i = start; i < end; i++) result += labels.get(i);
        return result;
    }

    private static double dot(double[] weights, double[] features) {
        double result = weights[0];
        for (int i = 0; i < FEATURE_COUNT; i++) result += weights[i + 1] * features[i];
        return result;
    }

    private static double sigmoid(double value) {
        double safe = clamp(value, -30, 30);
        return 1.0 / (1.0 + Math.exp(-safe));
    }

    private static double meanClose(List<Candle> candles, int start, int end) {
        double sum = 0;
        for (int i = start; i <= end; i++) sum += candles.get(i).close;
        return sum / (end - start + 1);
    }

    private static double standardDeviation(double[] values) {
        double mean = 0;
        for (double value : values) mean += value;
        mean /= values.length;
        double sum = 0;
        for (double value : values) {
            double delta = value - mean;
            sum += delta * delta;
        }
        return Math.sqrt(sum / Math.max(1, values.length - 1));
    }

    private static double effectiveVolume(Candle candle) {
        return candle.quoteVolume > 0 ? candle.quoteVolume : candle.baseVolume * candle.close;
    }

    private static double percent(double current, double previous) {
        return previous > 0 && Double.isFinite(current)
                ? (current / previous - 1.0) * 100.0 : 0;
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0
                ? (copy[middle - 1] + copy[middle]) / 2.0 : copy[middle];
    }

    private static boolean allFinite(double[] values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Dataset {
        final List<double[]> rows = new ArrayList<>();
        final List<Integer> labels = new ArrayList<>();
    }

    private static final class Standardizer {
        final double[] mean;
        final double[] scale;

        Standardizer(double[] mean, double[] scale) {
            this.mean = mean;
            this.scale = scale;
        }

        double[] transform(double[] values) {
            double[] result = new double[FEATURE_COUNT];
            for (int i = 0; i < FEATURE_COUNT; i++) {
                result[i] = clamp((values[i] - mean[i]) / scale[i], -6, 6);
            }
            return result;
        }
    }

    private static final class Validation {
        final double balancedAccuracy;
        final double brier;

        Validation(double balancedAccuracy, double brier) {
            this.balancedAccuracy = balancedAccuracy;
            this.brier = brier;
        }
    }
}
