package ai.arena.nabzcrypto.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import ai.arena.nabzcrypto.model.ScanResult;

import java.util.Locale;

/** Compact closed-candle chart with the exact entry zone, invalidation and ATR targets. */
public final class TradePlanChartView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint series = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint level = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    private double[] prices = new double[0];
    private double entryLow;
    private double entryHigh;
    private double invalidation;
    private double target1;
    private double target2;
    private double reference;
    private boolean directional;

    public TradePlanChartView(Context context) {
        super(context);
        setMinimumHeight(Theme.dp(context, 220));
        setBackground(Theme.rounded(Theme.SURFACE_2, Theme.dp(context, 14),
                Theme.dp(context, 1), Theme.BORDER));

        grid.setColor(Theme.withAlpha(Theme.MUTED, 30));
        grid.setStrokeWidth(Theme.dp(context, 1));
        series.setStyle(Paint.Style.STROKE);
        series.setStrokeWidth(Theme.dp(context, 2));
        series.setStrokeCap(Paint.Cap.ROUND);
        series.setStrokeJoin(Paint.Join.ROUND);
        area.setStyle(Paint.Style.FILL);
        level.setStyle(Paint.Style.STROKE);
        level.setStrokeWidth(Theme.dp(context, 1.2f));
        level.setPathEffect(new DashPathEffect(new float[]{
                Theme.dp(context, 5), Theme.dp(context, 4)}, 0));
        float textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9,
                context.getResources().getDisplayMetrics());
        label.setTypeface(Theme.bold(context));
        label.setTextSize(textSize);
        caption.setTypeface(Theme.regular(context));
        caption.setTextSize(textSize);
        caption.setColor(Theme.MUTED);
        dot.setStyle(Paint.Style.FILL);
    }

    public void setPlan(ScanResult result, int color) {
        if (result == null) return;
        prices = result.sparkline == null ? new double[0] : result.sparkline.clone();
        entryLow = result.zoneLow;
        entryHigh = result.zoneHigh;
        invalidation = result.invalidation;
        target1 = result.objective1;
        target2 = result.objective2;
        reference = result.referencePrice;
        directional = result.signal.isBuy() || result.signal.isSell();
        series.setColor(color);
        dot.setColor(color);
        setContentDescription(String.format(Locale.US,
                "نقشه سطوح %s؛ %s %s تا %s؛ ابطال %s؛ هدف یک %s؛ هدف دو %s",
                result.ticker.displaySymbol(), directional ? "ناحیه ورود" : "ناحیه پایش",
                Formatters.compactPrice(entryLow),
                Formatters.compactPrice(entryHigh), Formatters.compactPrice(invalidation),
                Formatters.compactPrice(target1), Formatters.compactPrice(target2)));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float left = Theme.dp(getContext(), 12);
        float top = Theme.dp(getContext(), 25);
        float right = width - Theme.dp(getContext(), 94);
        float bottom = height - Theme.dp(getContext(), 18);
        if (right <= left || bottom <= top) return;

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double price : prices) {
            if (finitePositive(price)) {
                min = Math.min(min, price);
                max = Math.max(max, price);
            }
        }
        double[] levels = {entryLow, entryHigh, invalidation, target1, target2, reference};
        for (double price : levels) {
            if (finitePositive(price)) {
                min = Math.min(min, price);
                max = Math.max(max, price);
            }
        }
        if (!finitePositive(min) || !finitePositive(max)) return;
        double rawRange = Math.max(Math.abs(max) * 1e-8, max - min);
        double padding = Math.max(Math.abs(reference) * 0.0005, rawRange * 0.10);
        min -= padding;
        max += padding;

        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, grid);
        }

        float entryTop = y(entryHigh, min, max, top, bottom);
        float entryBottom = y(entryLow, min, max, top, bottom);
        area.setColor(Theme.withAlpha(Theme.CYAN, 30));
        canvas.drawRect(new RectF(left, Math.min(entryTop, entryBottom), right,
                Math.max(entryTop, entryBottom)), area);
        drawHorizontal(canvas, entryLow, min, max, top, bottom, left, right, Theme.CYAN);
        drawHorizontal(canvas, entryHigh, min, max, top, bottom, left, right, Theme.CYAN);
        drawHorizontal(canvas, invalidation, min, max, top, bottom, left, right, Theme.RED);
        drawHorizontal(canvas, target1, min, max, top, bottom, left, right, Theme.GREEN);
        drawHorizontal(canvas, target2, min, max, top, bottom, left, right, Theme.GREEN);

        if (prices.length >= 2) {
            Path path = new Path();
            int validIndex = 0;
            int validCount = 0;
            for (double price : prices) if (finitePositive(price)) validCount++;
            float endX = left;
            float endY = y(reference, min, max, top, bottom);
            for (double price : prices) {
                if (!finitePositive(price)) continue;
                float x = validCount <= 1 ? left : left + (right - left) * validIndex / (validCount - 1f);
                float py = y(price, min, max, top, bottom);
                if (validIndex == 0) path.moveTo(x, py); else path.lineTo(x, py);
                endX = x;
                endY = py;
                validIndex++;
            }
            canvas.drawPath(path, series);
            canvas.drawCircle(endX, endY, Theme.dp(getContext(), 3), dot);
        }

        drawTags(canvas, min, max, top, bottom, right);

        canvas.drawText("5m • " + prices.length + " closed", left,
                Theme.dp(getContext(), 16), caption);
    }

    private void drawHorizontal(Canvas canvas, double price, double min, double max,
                                float top, float bottom, float left, float right, int color) {
        if (!finitePositive(price)) return;
        level.setColor(Theme.withAlpha(color, 180));
        float y = y(price, min, max, top, bottom);
        canvas.drawLine(left, y, right, y, level);
    }

    private void drawTags(Canvas canvas, double min, double max,
                          float top, float bottom, float right) {
        double entryMid = (entryLow + entryHigh) / 2.0;
        double[] values = {entryMid, invalidation, target1, target2};
        String[] texts = {
                (directional ? "ENTRY " : "WATCH ") + Formatters.compactPrice(entryMid),
                "STOP " + Formatters.compactPrice(invalidation),
                "TP1 " + Formatters.compactPrice(target1),
                "TP2 " + Formatters.compactPrice(target2)
        };
        int[] colors = {Theme.CYAN, Theme.RED, Theme.GREEN, Theme.GREEN};
        float[] actual = new float[values.length];
        float[] baseline = new float[values.length];
        int[] order = {0, 1, 2, 3};
        for (int i = 0; i < values.length; i++) actual[i] = y(values[i], min, max, top, bottom);
        for (int i = 0; i < order.length - 1; i++) {
            for (int j = i + 1; j < order.length; j++) {
                if (actual[order[j]] < actual[order[i]]) {
                    int swap = order[i];
                    order[i] = order[j];
                    order[j] = swap;
                }
            }
        }
        float gap = label.getTextSize() + Theme.dp(getContext(), 2);
        float minimum = top + label.getTextSize();
        for (int position = 0; position < order.length; position++) {
            int index = order[position];
            float wanted = actual[index] + label.getTextSize() * 0.34f;
            minimum = Math.max(minimum, wanted);
            baseline[index] = minimum;
            minimum += gap;
        }
        float overflow = baseline[order[order.length - 1]] - bottom;
        if (overflow > 0) {
            for (int i = 0; i < baseline.length; i++) baseline[i] -= overflow;
        }
        float labelX = right + Theme.dp(getContext(), 6);
        for (int i = 0; i < values.length; i++) {
            label.setColor(colors[i]);
            canvas.drawLine(right, actual[i], labelX - Theme.dp(getContext(), 2),
                    baseline[i] - label.getTextSize() * 0.34f, grid);
            canvas.drawText(texts[i], labelX, baseline[i], label);
        }
    }

    private static float y(double price, double min, double max, float top, float bottom) {
        double normalized = (max - price) / Math.max(1e-12, max - min);
        return top + (float) normalized * (bottom - top);
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }
}
