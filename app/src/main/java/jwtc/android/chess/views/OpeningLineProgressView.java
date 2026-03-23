package jwtc.android.chess.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Horizontal progress under the board: fraction of the current opening branch completed.
 * Filled from the start (left); track is neutral grey.
 */
public class OpeningLineProgressView extends View {

    private float progress = 0f; // 0..1

    private final Paint trackPaint = new Paint();
    private final Paint fillPaint = new Paint();
    private final Paint strokePaint = new Paint();

    public OpeningLineProgressView(Context context) {
        super(context);
        init();
    }

    public OpeningLineProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(0xFFDDDDDD);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0xFF4A8C4A);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(0xFF888888);
        strokePaint.setStrokeWidth(2);
    }

    /**
     * @param p completion in [0, 1]
     */
    public void setProgress(float p) {
        if (p < 0f) p = 0f;
        else if (p > 1f) p = 1f;
        if (this.progress != p) {
            this.progress = p;
            invalidate();
        }
    }

    public float getProgress() {
        return progress;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawRect(0, 0, w, h, trackPaint);

        int fillW = Math.round(w * progress);
        if (fillW > 0) {
            canvas.drawRect(0, 0, fillW, h, fillPaint);
        }

        canvas.drawRect(0, 0, w, h, strokePaint);
    }
}
