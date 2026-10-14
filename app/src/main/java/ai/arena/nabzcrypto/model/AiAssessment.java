package ai.arena.nabzcrypto.model;

import java.util.Locale;

/**
 * Output of the on-device time-series classifier. Values describe the model itself and are not
 * a promise of market direction, profit probability, or personalized financial advice.
 */
public final class AiAssessment {
    public final boolean trained;
    public final boolean reliable;
    public final double probabilityUp;
    public final int confidence;
    public final double validationAccuracy;
    public final double validationBrier;
    public final int trainingSamples;
    public final int validationSamples;
    public final int vote;

    public AiAssessment(boolean trained, boolean reliable, double probabilityUp, int confidence,
                        double validationAccuracy, double validationBrier,
                        int trainingSamples, int validationSamples, int vote) {
        this.trained = trained;
        this.reliable = trained && reliable;
        this.probabilityUp = clampFinite(probabilityUp, 0, 1, 0.5);
        this.confidence = clampInt(confidence, 0, 100);
        this.validationAccuracy = clampFinite(validationAccuracy, 0, 1, 0);
        this.validationBrier = clampFinite(validationBrier, 0, 1, 1);
        this.trainingSamples = Math.max(0, trainingSamples);
        this.validationSamples = Math.max(0, validationSamples);
        this.vote = this.reliable ? clampInt(vote, -12, 12) : 0;
    }

    public static AiAssessment unavailable() {
        return new AiAssessment(false, false, 0.5, 0, 0, 1, 0, 0, 0);
    }

    public String labelFa() {
        if (!trained) return "دادهٔ ناکافی";
        if (!reliable) return "مدل نامطمئن";
        if (vote > 0) return "گرایش صعودی";
        if (vote < 0) return "گرایش نزولی";
        return "بدون برتری جهت‌دار";
    }

    public String shortFa() {
        if (!trained) return "AI: دادهٔ کافی ندارد";
        int up = (int) Math.round(probabilityUp * 100.0);
        String direction = vote > 0 ? "↑" : vote < 0 ? "↓" : "≈";
        return String.format(Locale.US, "AI %s • خروجی صعود %d%% • اعتبار %d/100",
                direction, up, confidence);
    }

    public String explanationFa() {
        if (!trained) {
            return "برای آموزش و آزمون زمانی مدل، کندل بسته‌شدهٔ کافی یا هر دو کلاس صعود/نزول وجود نداشت؛ رأی AI در امتیاز دخالت نکرد.";
        }
        int accuracy = (int) Math.round(validationAccuracy * 100.0);
        int up = (int) Math.round(probabilityUp * 100.0);
        if (!reliable) {
            return String.format(Locale.US,
                    "مدل روی %d نمونه آموزش دید، اما دقت متوازن آن در %d نمونهٔ زمانیِ دیده‌نشده %d%% بود؛ بنابراین خروجی صعود %d%% فقط نمایش داده می‌شود و رأیی به سیگنال نمی‌دهد.",
                    trainingSamples, validationSamples, accuracy, up);
        }
        return String.format(Locale.US,
                "مدل محلی روی %d نمونهٔ قدیمی‌تر آموزش دید و در %d نمونهٔ زمانیِ دیده‌نشده دقت متوازن %d%% داشت. خروجی فعلی صعود %d%% است و فقط %s%d امتیاز محدود به تحلیل ترکیبی افزوده است.",
                trainingSamples, validationSamples, accuracy, up,
                vote >= 0 ? "+" : "", vote);
    }

    private static double clampFinite(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
