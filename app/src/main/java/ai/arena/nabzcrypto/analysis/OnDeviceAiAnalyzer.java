package ai.arena.nabzcrypto.analysis;

import ai.arena.nabzcrypto.model.AiAssessment;
import ai.arena.nabzcrypto.model.Candle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic on-device ensemble. Two three-class softmax models predict meaningful up, down, or
 * neutral movement at 15m and 30m horizons. Quality is measured with purged walk-forward folds;
 * final models are then refit on all labeled history. Nothing is uploaded.
 */
public final class OnDeviceAiAnalyzer {
    private static final int DOWN = 0;
    private static final int NEUTRAL = 1;
    private static final int UP = 2;
    private static final int CLASS_COUNT = 3;
    private static final int LOOKBACK = 24;
    private static final int FEATURE_COUNT = 10;
    private static final int MIN_TRAINING_ROWS = 70;
    private static final int MIN_VALIDATION_ROWS = 36;
    private static final int EPOCHS = 105;
    private static final double L2 = 0.020;

    private OnDeviceAiAnalyzer() {}

    public static AiAssessment analyze(List<Candle> input) {
        List<Candle> candles = clean(input);
        if (candles.size() < 175 || !contiguous(candles, candles.size() - 1, LOOKBACK)) {
            return AiAssessment.unavailable();
        }

        HorizonModel shortModel = fitHorizon(candles, 3);
        HorizonModel longModel = fitHorizon(candles, 6);
        List<HorizonModel> trained = new ArrayList<>();
        if (shortModel.trained) trained.add(shortModel);
        if (longModel.trained) trained.add(longModel);
        if (trained.isEmpty()) return AiAssessment.unavailable();

        int reliableCount = 0;
        for (HorizonModel model : trained) if (model.reliable) reliableCount++;
        double[] combined = new double[CLASS_COUNT];
        double totalWeight = 0;
        int trainingSamples = 0;
        int validationSamples = 0;
        int folds = 0;
        for (HorizonModel model : trained) {
            // Once at least one horizon passes the gate, a rejected horizon must not affect the vote.
            if (reliableCount > 0 && !model.reliable) continue;
            double quality = clamp((model.macroRecall - 1.0 / 3.0) / 0.25, 0, 1);
            double weight = model.reliable ? 1.0 + quality : 1.0;
            for (int c = 0; c < CLASS_COUNT; c++) combined[c] += model.probabilities[c] * weight;
            totalWeight += weight;
            trainingSamples = Math.max(trainingSamples, model.trainingSamples);
            validationSamples += model.validationSamples;
            folds += model.folds;
        }
        for (int c = 0; c < CLASS_COUNT; c++) combined[c] /= Math.max(1e-12, totalWeight);

        int shortDirection = shortModel.reliable ? direction(shortModel.probabilities) : 0;
        int longDirection = longModel.reliable ? direction(longModel.probabilities) : 0;
        boolean conflict = shortModel.reliable && longModel.reliable
                && shortDirection != 0 && longDirection != 0 && shortDirection != longDirection;
        boolean agreement = shortModel.reliable && longModel.reliable
                && shortDirection != 0 && shortDirection == longDirection;
        boolean reliable = reliableCount > 0 && !conflict;

        double accuracySum = 0;
        double brierSum = 0;
        double metricWeight = 0;
        for (HorizonModel model : trained) {
            if (reliableCount > 0 && !model.reliable) continue;
            double weight = Math.max(1, model.validationSamples);
            accuracySum += model.macroRecall * weight;
            brierSum += model.brier * weight;
            metricWeight += weight;
        }
        double accuracy = metricWeight > 0 ? accuracySum / metricWeight : 0;
        double brier = metricWeight > 0 ? brierSum / metricWeight : 1;

        int direction = reliable ? direction(combined) : 0;
        double directional = direction > 0 ? combined[UP] : direction < 0 ? combined[DOWN] : 0;
        double alternative = direction > 0 ? Math.max(combined[DOWN], combined[NEUTRAL])
                : direction < 0 ? Math.max(combined[UP], combined[NEUTRAL]) : 1;
        double edge = Math.max(0, directional - alternative);
        double quality = clamp((accuracy - 1.0 / 3.0) / 0.25, 0, 1);
        double calibration = clamp((0.222 - brier) / 0.11, 0, 1);
        double agreementFactor = agreement ? 1.0 : reliableCount == 1 ? 0.70 : 0.55;
        int confidence = (int) Math.round(clamp(100 * (edge / 0.55)
                * (0.30 + 0.45 * quality + 0.25 * calibration) * agreementFactor, 0, 95));

        int vote = 0;
        if (reliable && direction != 0 && edge >= 0.07 && confidence >= 18) {
            double qualityFactor = 0.25 + 0.75 * quality;
            int magnitude = (int) Math.round(clamp((2 + edge * 15 + quality * 3)
                    * qualityFactor * agreementFactor, 2, agreement ? 12 : 7));
            vote = direction > 0 ? magnitude : -magnitude;
        }

        return new AiAssessment(true, reliable,
                combined[UP], combined[DOWN], combined[NEUTRAL], confidence,
                accuracy, brier, trainingSamples, validationSamples, vote,
                agreement, reliableCount > 0 ? reliableCount : trained.size(), folds);
    }

    private static HorizonModel fitHorizon(List<Candle> candles, int horizon) {
        Dataset data = dataset(candles, horizon);
        if (data.rows.size() < MIN_TRAINING_ROWS + MIN_VALIDATION_ROWS + horizon) {
            return HorizonModel.unavailable();
        }

        int firstValidation = Math.max(MIN_TRAINING_ROWS + horizon,
                (int) Math.floor(data.rows.size() * 0.60));
        int midpoint = firstValidation + (data.rows.size() - firstValidation) / 2;
        int[] starts = {firstValidation, midpoint};
        int[] ends = {midpoint, data.rows.size()};
        List<double[]> outOfFold = new ArrayList<>();
        List<Integer> outLabels = new ArrayList<>();
        int completedFolds = 0;

        for (int fold = 0; fold < starts.length; fold++) {
            int validationStart = starts[fold];
            int validationEnd = ends[fold];
            int trainEnd = validationStart - horizon; // Purge overlapping future labels.
            int[] counts = classCounts(data.labels, 0, trainEnd);
            if (trainEnd < MIN_TRAINING_ROWS || !enoughClasses(counts, 8, 4)) continue;
            Standardizer standardizer = fitStandardizer(data.rows, trainEnd);
            double[][] weights = train(data, trainEnd, counts, standardizer);
            for (int i = validationStart; i < validationEnd; i++) {
                outOfFold.add(softmax(weights, standardizer.transform(data.rows.get(i))));
                outLabels.add(data.labels.get(i));
            }
            completedFolds++;
        }

        int[] finalCounts = classCounts(data.labels, 0, data.labels.size());
        if (!enoughClasses(finalCounts, 10, 5)) return HorizonModel.unavailable();
        Standardizer finalStandardizer = fitStandardizer(data.rows, data.rows.size());
        double[][] finalWeights = train(data, data.rows.size(), finalCounts, finalStandardizer);

        double temperature = bestTemperature(outOfFold, outLabels);
        Validation validation = validate(outOfFold, outLabels, temperature);
        double[] current = features(candles, candles.size() - 1);
        double[] probabilities = calibrate(
                softmax(finalWeights, finalStandardizer.transform(current)), temperature);
        boolean reliable = completedFolds == 2
                && validation.samples >= MIN_VALIDATION_ROWS
                && validation.classMinimum >= 5
                && validation.macroRecall >= 0.40
                && validation.brier <= 0.215;
        return new HorizonModel(true, reliable, probabilities,
                validation.macroRecall, validation.brier, data.rows.size(),
                validation.samples, completedFolds);
    }

    private static Dataset dataset(List<Candle> candles, int horizon) {
        Dataset result = new Dataset();
        for (int t = LOOKBACK; t + horizon < candles.size(); t++) {
            if (!contiguous(candles, t + horizon, LOOKBACK + horizon)) continue;
            double[] row = features(candles, t);
            if (!allFinite(row)) continue;
            double futureReturn = percent(candles.get(t + horizon).close, candles.get(t).close);
            double noise = clamp(recentVolatility(candles, t) * Math.sqrt(horizon) * 0.30,
                    0.06, 0.90);
            int label = futureReturn > noise ? UP : futureReturn < -noise ? DOWN : NEUTRAL;
            result.rows.add(row);
            result.labels.add(label);
        }
        return result;
    }

    private static double[] features(List<Candle> candles, int t) {
        double close = candles.get(t).close;
        double return1 = percent(close, candles.get(t - 1).close);
        double return3 = percent(close, candles.get(t - 3).close);
        double return6 = percent(close, candles.get(t - 6).close);
        double return12 = percent(close, candles.get(t - 12).close);
        double fast = meanClose(candles, t - 5, t);
        double slow = meanClose(candles, t - 17, t);
        double trendGap = percent(fast, slow);
        double volatility = recentVolatility(candles, t);

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
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (int i = t - 23; i <= t; i++) {
            low = Math.min(low, candles.get(i).low);
            high = Math.max(high, candles.get(i).high);
        }
        double channelPosition = high > low
                ? clamp(((close - low) / (high - low) - 0.5) * 2.0, -1, 1) : 0;
        return new double[]{return1, return3, return6, return12, trendGap,
                volatility, volumeLogRatio, body, rangePosition, channelPosition};
    }

    private static double recentVolatility(List<Candle> candles, int t) {
        double[] returns = new double[12];
        for (int i = 0; i < returns.length; i++) {
            int index = t - returns.length + 1 + i;
            returns[i] = percent(candles.get(index).close, candles.get(index - 1).close);
        }
        return standardDeviation(returns);
    }

    private static double[][] train(Dataset data, int trainEnd, int[] counts,
                                    Standardizer standardizer) {
        double[][] weights = new double[CLASS_COUNT][FEATURE_COUNT + 1];
        double[] classWeight = new double[CLASS_COUNT];
        for (int c = 0; c < CLASS_COUNT; c++) {
            classWeight[c] = clamp(trainEnd / (double) (CLASS_COUNT * counts[c]), 0.35, 4.0);
        }
        for (int epoch = 0; epoch < EPOCHS; epoch++) {
            double[][] gradient = new double[CLASS_COUNT][FEATURE_COUNT + 1];
            for (int i = 0; i < trainEnd; i++) {
                double[] x = standardizer.transform(data.rows.get(i));
                int y = data.labels.get(i);
                double[] probability = softmax(weights, x);
                for (int c = 0; c < CLASS_COUNT; c++) {
                    double error = (probability[c] - (y == c ? 1.0 : 0.0)) * classWeight[y];
                    gradient[c][0] += error;
                    for (int j = 0; j < FEATURE_COUNT; j++) {
                        gradient[c][j + 1] += error * x[j];
                    }
                }
            }
            double learningRate = 0.12 / Math.sqrt(1.0 + epoch * 0.04);
            for (int c = 0; c < CLASS_COUNT; c++) {
                weights[c][0] = clamp(weights[c][0]
                        - learningRate * gradient[c][0] / trainEnd, -8, 8);
                for (int j = 1; j <= FEATURE_COUNT; j++) {
                    double regularized = gradient[c][j] / trainEnd + L2 * weights[c][j];
                    weights[c][j] = clamp(weights[c][j]
                            - learningRate * regularized, -8, 8);
                }
            }
        }
        return weights;
    }

    private static double[] softmax(double[][] weights, double[] features) {
        double[] logits = new double[CLASS_COUNT];
        double max = -Double.MAX_VALUE;
        for (int c = 0; c < CLASS_COUNT; c++) {
            logits[c] = weights[c][0];
            for (int j = 0; j < FEATURE_COUNT; j++) logits[c] += weights[c][j + 1] * features[j];
            max = Math.max(max, logits[c]);
        }
        double total = 0;
        for (int c = 0; c < CLASS_COUNT; c++) {
            logits[c] = Math.exp(clamp(logits[c] - max, -30, 0));
            total += logits[c];
        }
        for (int c = 0; c < CLASS_COUNT; c++) logits[c] /= Math.max(1e-12, total);
        return logits;
    }

    private static double bestTemperature(List<double[]> probabilities, List<Integer> labels) {
        if (probabilities.isEmpty()) return 1.0;
        double[] candidates = {0.80, 1.00, 1.25, 1.50, 2.00};
        double best = 1.0;
        double bestBrier = Double.MAX_VALUE;
        for (double temperature : candidates) {
            double brier = brier(probabilities, labels, temperature);
            if (brier < bestBrier) {
                bestBrier = brier;
                best = temperature;
            }
        }
        return best;
    }

    private static Validation validate(List<double[]> probabilities, List<Integer> labels,
                                       double temperature) {
        if (probabilities.isEmpty()) return new Validation(0, 1, 0, 0);
        int[][] confusion = new int[CLASS_COUNT][CLASS_COUNT];
        int[] actual = new int[CLASS_COUNT];
        double squared = 0;
        for (int i = 0; i < probabilities.size(); i++) {
            double[] calibrated = calibrate(probabilities.get(i), temperature);
            int label = labels.get(i);
            int predicted = argMax(calibrated);
            confusion[label][predicted]++;
            actual[label]++;
            for (int c = 0; c < CLASS_COUNT; c++) {
                double error = calibrated[c] - (label == c ? 1.0 : 0.0);
                squared += error * error;
            }
        }
        double macroRecall = 0;
        int classMinimum = Integer.MAX_VALUE;
        for (int c = 0; c < CLASS_COUNT; c++) {
            macroRecall += actual[c] > 0 ? confusion[c][c] / (double) actual[c] : 0;
            classMinimum = Math.min(classMinimum, actual[c]);
        }
        macroRecall /= CLASS_COUNT;
        double brier = squared / (probabilities.size() * CLASS_COUNT);
        return new Validation(macroRecall, brier, probabilities.size(), classMinimum);
    }

    private static double brier(List<double[]> probabilities, List<Integer> labels,
                                double temperature) {
        double squared = 0;
        for (int i = 0; i < probabilities.size(); i++) {
            double[] calibrated = calibrate(probabilities.get(i), temperature);
            for (int c = 0; c < CLASS_COUNT; c++) {
                double error = calibrated[c] - (labels.get(i) == c ? 1.0 : 0.0);
                squared += error * error;
            }
        }
        return squared / Math.max(1, probabilities.size() * CLASS_COUNT);
    }

    private static double[] calibrate(double[] probability, double temperature) {
        double[] result = new double[CLASS_COUNT];
        double total = 0;
        for (int c = 0; c < CLASS_COUNT; c++) {
            result[c] = Math.pow(Math.max(1e-9, probability[c]), 1.0 / temperature);
            total += result[c];
        }
        for (int c = 0; c < CLASS_COUNT; c++) result[c] /= total;
        return result;
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

    private static int direction(double[] probability) {
        int direction = probability[UP] >= probability[DOWN] ? 1 : -1;
        double directional = direction > 0 ? probability[UP] : probability[DOWN];
        double alternative = direction > 0 ? Math.max(probability[DOWN], probability[NEUTRAL])
                : Math.max(probability[UP], probability[NEUTRAL]);
        return directional - alternative >= 0.05 ? direction : 0;
    }

    private static boolean enoughClasses(int[] counts, int directionalMinimum, int neutralMinimum) {
        return counts[UP] >= directionalMinimum && counts[DOWN] >= directionalMinimum
                && counts[NEUTRAL] >= neutralMinimum;
    }

    private static int[] classCounts(List<Integer> labels, int start, int end) {
        int[] result = new int[CLASS_COUNT];
        for (int i = start; i < end; i++) result[labels.get(i)]++;
        return result;
    }

    private static int argMax(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) if (values[i] > values[best]) best = i;
        return best;
    }

    private static boolean contiguous(List<Candle> candles, int end, int lookback) {
        int start = Math.max(1, end - lookback);
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
        final double macroRecall;
        final double brier;
        final int samples;
        final int classMinimum;

        Validation(double macroRecall, double brier, int samples, int classMinimum) {
            this.macroRecall = macroRecall;
            this.brier = brier;
            this.samples = samples;
            this.classMinimum = classMinimum;
        }
    }

    private static final class HorizonModel {
        final boolean trained;
        final boolean reliable;
        final double[] probabilities;
        final double macroRecall;
        final double brier;
        final int trainingSamples;
        final int validationSamples;
        final int folds;

        HorizonModel(boolean trained, boolean reliable, double[] probabilities,
                     double macroRecall, double brier, int trainingSamples,
                     int validationSamples, int folds) {
            this.trained = trained;
            this.reliable = reliable;
            this.probabilities = probabilities;
            this.macroRecall = macroRecall;
            this.brier = brier;
            this.trainingSamples = trainingSamples;
            this.validationSamples = validationSamples;
            this.folds = folds;
        }

        static HorizonModel unavailable() {
            return new HorizonModel(false, false,
                    new double[]{1.0 / 3, 1.0 / 3, 1.0 / 3},
                    0, 1, 0, 0, 0);
        }
    }
}
