package com.downloadmapimage.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * A header view that:
 *  - Fills the status-bar area in solid red (#C62828)
 *  - Draws a decorative wavy / droplet bottom edge in the same red
 *  - No text or icon — purely decorative
 */
public class WavyHeaderView extends View {

    private static final int COLOR_RED = 0xFFC62828;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    // Height of the flat red area above the wave (set in onMeasure from window insets)
    private int statusBarHeight = 0;
    // Height of the wavy section below the flat area
    private static final int WAVE_HEIGHT_DP = 28;

    public WavyHeaderView(Context context) {
        super(context);
        init();
    }

    public WavyHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WavyHeaderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        fillPaint.setColor(COLOR_RED);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    /** Called by MainActivity after it resolves the real status bar height. */
    public void setStatusBarHeight(int px) {
        this.statusBarHeight = px;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int waveHeightPx = dpToPx(WAVE_HEIGHT_DP);
        // Total height = status bar + a small flat padding + wave amplitude
        int h = statusBarHeight + dpToPx(4) + waveHeightPx;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        int waveH = dpToPx(WAVE_HEIGHT_DP);
        // y where the flat red area ends and the wave starts
        int flatBottom = h - waveH;

        path.reset();

        // Start top-left
        path.moveTo(0, 0);
        // Top-right
        path.lineTo(w, 0);
        // Down the right edge to wave start
        path.lineTo(w, flatBottom);

        // --- Wavy / droplet bottom edge (right → left) ---
        // We draw a series of unequal cubic bezier curves to create the
        // "dripping" effect: some drops are deep, some shallow.
        // All control points are tuned so tips look like water droplets.

        float segW = w / 5f; // 5 drip segments across the width

        // Drip depths (as fraction of waveH): vary them for organic look
        float[] depths = { 0.95f, 0.55f, 1.0f, 0.65f, 0.80f };

        // Build the wave path from right to left
        // Each segment: one cubic bezier representing one drip
        for (int i = 4; i >= 0; i--) {
            float x1 = segW * (i + 1); // right x of this segment
            float x0 = segW * i;       // left x of this segment
            float midX = (x0 + x1) / 2f;
            float tipY = flatBottom + waveH * depths[i];
            float cx1 = x1 - segW * 0.25f;
            float cx2 = midX + segW * 0.15f;
            float cx3 = midX - segW * 0.15f;
            float cx4 = x0 + segW * 0.25f;

            // Right shoulder → tip
            path.cubicTo(cx1, flatBottom, cx2, tipY, midX, tipY);
            // Tip → left shoulder
            path.cubicTo(cx3, tipY, cx4, flatBottom, x0, flatBottom);
        }

        // Close: left edge up to top-left
        path.lineTo(0, flatBottom);
        path.lineTo(0, 0);
        path.close();

        canvas.drawPath(path, fillPaint);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
