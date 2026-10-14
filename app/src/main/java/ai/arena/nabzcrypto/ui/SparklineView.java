package ai.arena.nabzcrypto.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public final class SparklineView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baseline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double[] values = new double[0];

    public SparklineView(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Theme.dp(context, 2));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        baseline.setColor(Theme.withAlpha(Theme.MUTED, 35));
        baseline.setStrokeWidth(Theme.dp(context, 1));
    }

    public void setData(double[] data, int color) {
        values = data == null ? new double[0] : data.clone();
        line.setColor(color);
        dot.setColor(color);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        canvas.drawLine(0, height * 0.72f, width, height * 0.72f, baseline);
        if (values.length < 2) return;

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double value : values) {
            if (!Double.isFinite(value)) continue;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) return;
        double range = Math.max(Math.abs(max) * 1e-9, max - min);
        float pad = Theme.dp(getContext(), 3);
        float usableWidth = Math.max(1, width - 2 * pad);
        float usableHeight = Math.max(1, height - 2 * pad);
        Path path = new Path();
        for (int i = 0; i < values.length; i++) {
            float x = pad + usableWidth * i / (values.length - 1f);
            float y = pad + (float) ((max - values[i]) / range) * usableHeight;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, line);
        float endX = pad + usableWidth;
        float endY = pad + (float) ((max - values[values.length - 1]) / range) * usableHeight;
        canvas.drawCircle(endX, endY, Theme.dp(getContext(), 2.5f), dot);
    }
}
