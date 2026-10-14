package ai.arena.nabzcrypto.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.TradeChartData;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Interactive local candlestick chart with exact entry, invalidation and ATR targets. */
public final class TradePlanChartView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint level = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;

    private TradeChartData data = TradeChartData.empty();
    private double entryLow;
    private double entryHigh;
    private double invalidation;
    private double target1;
    private double target2;
    private double reference;
    private boolean directional;
    private long signalTime;
    private int visibleCount = 48;
    private int rightOffset;
    private float downX;
    private float downY;
    private float lastX;
    private boolean horizontalDrag;

    public TradePlanChartView(Context context) {
        super(context);
        setMinimumHeight(Theme.dp(context, 250));
        setBackground(Theme.rounded(Theme.SURFACE_2, Theme.dp(context, 14),
                Theme.dp(context, 1), Theme.BORDER));

        grid.setColor(Theme.withAlpha(Theme.MUTED, 30));
        grid.setStrokeWidth(Theme.dp(context, 1));
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
        wick.setStrokeWidth(Theme.dp(context, 1));
        body.setStyle(Paint.Style.FILL);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        int maximum = Math.max(16, Math.min(96, data.size()));
                        int next = Math.round(visibleCount / detector.getScaleFactor());
                        visibleCount = Math.max(16, Math.min(maximum, next));
                        clampOffset();
                        invalidate();
                        return true;
                    }
                });
    }

    public void setPlan(ScanResult result, int ignoredColor) {
        if (result == null) return;
        data = result.chartData == null ? TradeChartData.empty() : result.chartData;
        if (data.size() == 0 && result.sparkline != null && result.sparkline.length > 0) {
            int count = result.sparkline.length;
            long[] times = new long[count];
            double[] values = result.sparkline.clone();
            for (int i = 0; i < count; i++) times[i] = result.candleClosedAt - (count - i) * 300_000L;
            data = new TradeChartData(times, values, values, values, values);
        }
        entryLow = result.zoneLow;
        entryHigh = result.zoneHigh;
        invalidation = result.invalidation;
        target1 = result.objective1;
        target2 = result.objective2;
        reference = result.referencePrice;
        directional = result.signal.isBuy() || result.signal.isSell();
        visibleCount = Math.max(16, Math.min(48, Math.max(16, data.size())));
        rightOffset = 0;
        setContentDescription(String.format(Locale.US,
                "نمودار کندلی تعاملی %s؛ %s %s تا %s؛ ابطال %s؛ هدف یک %s؛ هدف دو %s",
                result.ticker.displaySymbol(), directional ? "ناحیه ورود" : "ناحیه پایش",
                Formatters.compactPrice(entryLow), Formatters.compactPrice(entryHigh),
                Formatters.compactPrice(invalidation), Formatters.compactPrice(target1),
                Formatters.compactPrice(target2)));
        invalidate();
    }

    /** Replaces moving analysis levels with the frozen levels of an active journal signal. */
    public void setTrackedLevels(double entry, double stop, double firstTarget,
                                 double secondTarget) {
        if (!finitePositive(entry)) return;
        entryLow = entryHigh = entry;
        invalidation = stop;
        target1 = firstTarget;
        target2 = secondTarget;
        directional = true;
        invalidate();
    }

    /** Marks the exact journal issuance candle; zero removes the marker. */
    public void setSignalTime(long signalTime) {
        this.signalTime = Math.max(0L, signalTime);
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = event.getY();
                horizontalDrag = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastX;
                float totalX = event.getX() - downX;
                float totalY = event.getY() - downY;
                if (!scaleDetector.isInProgress() && Math.abs(totalX) > Math.abs(totalY)
                        && Math.abs(totalX) > Theme.dp(getContext(), 5)) {
                    horizontalDrag = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    float plotWidth = Math.max(1, getWidth() - Theme.dp(getContext(), 112));
                    int shift = Math.round(dx * visibleCount / plotWidth);
                    if (shift != 0) {
                        rightOffset += shift;
                        clampOffset();
                        invalidate();
                    }
                }
                lastX = event.getX();
                return horizontalDrag || scaleDetector.isInProgress();
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                horizontalDrag = false;
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || data.size() == 0) return;

        float left = Theme.dp(getContext(), 12);
        float top = Theme.dp(getContext(), 27);
        float right = width - Theme.dp(getContext(), 94);
        float bottom = height - Theme.dp(getContext(), 20);
        if (right <= left || bottom <= top) return;

        int end = Math.max(1, data.size() - rightOffset);
        int start = Math.max(0, end - visibleCount);
        int count = Math.max(1, end - start);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = start; i < end; i++) {
            if (finitePositive(data.lows[i])) min = Math.min(min, data.lows[i]);
            if (finitePositive(data.highs[i])) max = Math.max(max, data.highs[i]);
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
        double padding = Math.max(Math.abs(reference) * 0.0005, rawRange * 0.08);
        min -= padding;
        max += padding;

        for (int i = 0; i <= 4; i++) {
            float py = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, py, right, py, grid);
        }

        float entryTop = y(entryHigh, min, max, top, bottom);
        float entryBottom = y(entryLow, min, max, top, bottom);
        area.setColor(Theme.withAlpha(Theme.CYAN, 30));
        canvas.drawRect(new RectF(left, Math.min(entryTop, entryBottom), right,
                Math.max(entryTop, entryBottom)), area);

        float slot = (right - left) / count;
        float candleWidth = Math.max(Theme.dp(getContext(), 1.5f), slot * 0.58f);
        for (int index = start; index < end; index++) {
            float x = left + slot * (index - start + 0.5f);
            boolean rising = data.closes[index] >= data.opens[index];
            int color = rising ? Theme.GREEN : Theme.RED;
            wick.setColor(Theme.withAlpha(color, 210));
            body.setColor(Theme.withAlpha(color, 225));
            float highY = y(data.highs[index], min, max, top, bottom);
            float lowY = y(data.lows[index], min, max, top, bottom);
            float openY = y(data.opens[index], min, max, top, bottom);
            float closeY = y(data.closes[index], min, max, top, bottom);
            canvas.drawLine(x, highY, x, lowY, wick);
            float bodyTop = Math.min(openY, closeY);
            float bodyBottom = Math.max(openY, closeY);
            if (bodyBottom - bodyTop < Theme.dp(getContext(), 1)) {
                bodyBottom = bodyTop + Theme.dp(getContext(), 1);
            }
            canvas.drawRect(x - candleWidth / 2, bodyTop,
                    x + candleWidth / 2, bodyBottom, body);
        }

        drawSignalMarker(canvas, start, end, slot, left, top, bottom);
        drawHorizontal(canvas, entryLow, min, max, top, bottom, left, right, Theme.CYAN);
        drawHorizontal(canvas, entryHigh, min, max, top, bottom, left, right, Theme.CYAN);
        drawHorizontal(canvas, invalidation, min, max, top, bottom, left, right, Theme.RED);
        drawHorizontal(canvas, target1, min, max, top, bottom, left, right, Theme.GREEN);
        drawHorizontal(canvas, target2, min, max, top, bottom, left, right, Theme.GREEN);
        drawTags(canvas, min, max, top, bottom, right);

        canvas.drawText("5m • " + count + "/" + data.size() + " • pinch / drag", left,
                Theme.dp(getContext(), 17), caption);
    }

    private void drawSignalMarker(Canvas canvas, int start, int end, float slot,
                                  float left, float top, float bottom) {
        if (signalTime <= 0 || data.openTimes.length == 0) return;
        int nearest = -1;
        long distance = Long.MAX_VALUE;
        for (int i = 0; i < data.openTimes.length; i++) {
            long current = Math.abs(data.openTimes[i] - signalTime);
            if (current < distance) {
                distance = current;
                nearest = i;
            }
        }
        if (nearest < start || nearest >= end) return;
        float x = left + slot * (nearest - start + 0.5f);
        level.setColor(Theme.withAlpha(Theme.AMBER, 225));
        canvas.drawLine(x, top, x, bottom, level);
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(signalTime));
        caption.setColor(Theme.AMBER);
        canvas.drawText("صدور " + time, Math.max(left, x - Theme.dp(getContext(), 22)),
                top - Theme.dp(getContext(), 5), caption);
        caption.setColor(Theme.MUTED);
    }

    private void drawHorizontal(Canvas canvas, double price, double min, double max,
                                float top, float bottom, float left, float right, int color) {
        if (!finitePositive(price)) return;
        level.setColor(Theme.withAlpha(color, 180));
        float py = y(price, min, max, top, bottom);
        canvas.drawLine(left, py, right, py, level);
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

    private void clampOffset() {
        rightOffset = Math.max(0, Math.min(Math.max(0, data.size() - visibleCount), rightOffset));
    }

    private static float y(double price, double min, double max, float top, float bottom) {
        double normalized = (max - price) / Math.max(1e-12, max - min);
        return top + (float) normalized * (bottom - top);
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }
}
