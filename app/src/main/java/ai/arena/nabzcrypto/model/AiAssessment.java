package ai.arena.nabzcrypto.model;

import java.util.Locale;

/**
 * Output of the on-device, multi-horizon time-series ensemble. Probabilities describe the model
 * classes and are not a promise of direction, profit probability, or personalized advice.
 */
public final class AiAssessment {
    public final boolean trained;
    public final boolean reliable;
    public final double probabilityUp;
    public final double probabilityDown;
    public final double probabilityNeutral;
    public final int confidence;
    public final double validationAccuracy;
    public final double validationBrier;
    public final int trainingSamples;
    public final int validationSamples;
    public final int vote;
    public final boolean horizonAgreement;
    public final int modelsUsed;
    public final int validationFolds;
    public final boolean driftDetected;
    public final double recentValidationAccuracy;

    public AiAssessment(boolean trained, boolean reliable,
                        double probabilityUp, double probabilityDown, double probabilityNeutral,
                        int confidence, double validationAccuracy, double validationBrier,
                        int trainingSamples, int validationSamples, int vote,
                        boolean horizonAgreement, int modelsUsed, int validationFolds) {
        this(trained, reliable, probabilityUp, probabilityDown, probabilityNeutral,
                confidence, validationAccuracy, validationBrier, trainingSamples,
                validationSamples, vote, horizonAgreement, modelsUsed, validationFolds,
                false, validationAccuracy);
    }

    public AiAssessment(boolean trained, boolean reliable,
                        double probabilityUp, double probabilityDown, double probabilityNeutral,
                        int confidence, double validationAccuracy, double validationBrier,
                        int trainingSamples, int validationSamples, int vote,
                        boolean horizonAgreement, int modelsUsed, int validationFolds,
                        boolean driftDetected, double recentValidationAccuracy) {
        this.trained = trained;
        this.reliable = trained && reliable;
        double up = finiteNonNegative(probabilityUp);
        double down = finiteNonNegative(probabilityDown);
        double neutral = finiteNonNegative(probabilityNeutral);
        double total = up + down + neutral;
        if (total <= 0) {
            up = down = neutral = 1.0 / 3.0;
            total = 1.0;
        }
        this.probabilityUp = up / total;
        this.probabilityDown = down / total;
        this.probabilityNeutral = neutral / total;
        this.confidence = clampInt(confidence, 0, 100);
        this.validationAccuracy = clampFinite(validationAccuracy, 0, 1, 0);
        this.validationBrier = clampFinite(validationBrier, 0, 1, 1);
        this.trainingSamples = Math.max(0, trainingSamples);
        this.validationSamples = Math.max(0, validationSamples);
        this.vote = this.reliable ? clampInt(vote, -12, 12) : 0;
        this.horizonAgreement = this.reliable && horizonAgreement;
        this.modelsUsed = trained ? clampInt(modelsUsed, 0, 2) : 0;
        this.validationFolds = trained ? Math.max(0, validationFolds) : 0;
        this.driftDetected = trained && driftDetected;
        this.recentValidationAccuracy = clampFinite(recentValidationAccuracy, 0, 1, 0);
    }

    public static AiAssessment unavailable() {
        return new AiAssessment(false, false, 1, 1, 1,
                0, 0, 1, 0, 0, 0, false, 0, 0);
    }

    public String labelFa() {
        if (!trained) return "دادهٔ ناکافی";
        if (driftDetected) return "افت کیفیت اخیر؛ AI غیرفعال";
        if (!reliable) return "مدل نامطمئن";
        if (vote > 0) return horizonAgreement ? "صعودی با توافق دو افق" : "گرایش صعودی محتاط";
        if (vote < 0) return horizonAgreement ? "نزولی با توافق دو افق" : "گرایش نزولی محتاط";
        return "امتناع از رأی جهت‌دار";
    }

    public String shortFa() {
        if (!trained) return "AI: دادهٔ کافی ندارد";
        int up = (int) Math.round(probabilityUp * 100.0);
        int down = (int) Math.round(probabilityDown * 100.0);
        String direction = vote > 0 ? "↑" : vote < 0 ? "↓" : "≈";
        return String.format(Locale.US, "AI %s • بالا %d%% / پایین %d%% • اعتبار %d/100",
                direction, up, down, confidence);
    }

    public String explanationFa() {
        if (!trained) {
            return "برای آموزش و آزمون walk-forward مدل سه‌کلاسه، کندل یا تنوع کلاس کافی نبود؛ AI رأیی به سیگنال نداد.";
        }
        int accuracy = (int) Math.round(validationAccuracy * 100.0);
        int up = (int) Math.round(probabilityUp * 100.0);
        int down = (int) Math.round(probabilityDown * 100.0);
        int neutral = (int) Math.round(probabilityNeutral * 100.0);
        if (!reliable) {
            int recent = (int) Math.round(recentValidationAccuracy * 100.0);
            String gate = driftDetected
                    ? "عملکرد پنجرهٔ اخیر به " + recent + "٪ افت کرده است"
                    : "گیت کیفیت یا توافق افق‌ها رد شده است";
            return String.format(Locale.US,
                    "ensemble روی %d نمونه ساخته شد، اما %s؛ در %d پیش‌بینی خارج‌نمونه و %d پنجرهٔ زمانی دقت متوازن %d%% بود. خروجی بالا/خنثی/پایین %d/%d/%d%% فقط نمایش داده می‌شود و در سیگنال دخالت ندارد.",
                    trainingSamples, gate, validationSamples, validationFolds, accuracy,
                    up, neutral, down);
        }
        String agreement = horizonAgreement ? "دو افق ۱۵ و ۳۰ دقیقه هم‌جهت‌اند"
                : "توافق جهت‌دار دو افق کامل نیست یا فقط یک افق معتبر است";
        return String.format(Locale.US,
                "مدل سه‌کلاسهٔ %d افقی با walk-forward روی %d پیش‌بینی خارج‌نمونه به دقت متوازن %d%% رسید؛ %s. خروجی بالا/خنثی/پایین %d/%d/%d%% است و رأی محدود %s%d دارد.",
                modelsUsed, validationSamples, accuracy, agreement,
                up, neutral, down, vote >= 0 ? "+" : "", vote);
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    private static double clampFinite(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
