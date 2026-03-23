package jwtc.android.chess.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Simple vertical evaluation bar: white on bottom, black on top.
 * Total height equals board side visually when placed next to the board.
 */
public class EvalBarView extends View {
    private final Paint paint = new Paint();
    private float evalCp = 0f; // + means white advantage (pawns, e.g. +1.0)

    /**
     * Linear mapping: eval is clamped to [-FULL_ADV_PAWNS, +FULL_ADV_PAWNS] and mapped to bar split.
     * Using tanh(cp/3) made ~+1.3 pawns look like ~70% white (too strong) and small refinements
     * (e.g. +1.30 → +1.12) barely moved the bar because tanh was already on the flat part.
     */
    private static final float FULL_ADV_PAWNS = 6f;

    public EvalBarView(Context context) {
        super(context);
    }

    public EvalBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setEvalCp(float evalCp) {
        if (this.evalCp != evalCp) {
            this.evalCp = evalCp;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Linear in pawns: 0 = 50/50, +FULL_ADV_PAWNS = all white, -FULL_ADV_PAWNS = all black.
        float t = evalCp / FULL_ADV_PAWNS;
        if (t < -1f) t = -1f;
        else if (t > 1f) t = 1f;
        float whiteShare = 0.5f + 0.5f * t;
        int whiteH = (int) Math.round(h * whiteShare);
        int blackH = h - whiteH;

        // black area (top)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        canvas.drawRect(0, 0, w, blackH, paint);

        // white area (bottom)
        paint.setColor(0xFFFFFFFF);
        canvas.drawRect(0, blackH, w, h, paint);

        // border
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFF888888);
        paint.setStrokeWidth(2);
        canvas.drawRect(0, 0, w, h, paint);
    }
}

