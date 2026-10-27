package vn.vuumi.pcdash.remote;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Frosted-glass surface without any blur pass: translucent fill and a single rim whose brightness fades
 * from top to bottom. One border only, so stacked lines never appear. Cheap enough for a polling screen.
 */
final class GlassDrawable extends Drawable {

    private static final int RIM_TOP = Color.argb(70, 255, 255, 255);
    private static final int RIM_BOTTOM = Color.argb(14, 255, 255, 255);

    private final int fillRgb;
    private final int fillAlpha;
    private final int solidAlpha;
    private final float[] radii;
    private final float strokePx;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private boolean solid;

    /**
     * @param fillColor   base color, its alpha is the translucent value
     * @param solidAlpha  alpha used instead when blur or transparency is unavailable (opaque fallback)
     * @param topRadiusPx  corner radius of the two top corners
     * @param bottomRadiusPx corner radius of the two bottom corners
     */
    GlassDrawable(int fillColor, int solidAlpha, float topRadiusPx, float bottomRadiusPx, float strokePx) {
        this.fillRgb = fillColor & 0x00FFFFFF;
        this.fillAlpha = Color.alpha(fillColor);
        this.solidAlpha = solidAlpha;
        this.radii = new float[]{topRadiusPx, topRadiusPx, topRadiusPx, topRadiusPx,
                bottomRadiusPx, bottomRadiusPx, bottomRadiusPx, bottomRadiusPx};
        this.strokePx = strokePx;
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(strokePx);
        applyAlpha();
    }

    /** Switch between the translucent look and the opaque fallback. */
    void setSolid(boolean solid) {
        if (this.solid != solid) {
            this.solid = solid;
            applyAlpha();
            invalidateSelf();
        }
    }

    private void applyAlpha() {
        fillPaint.setColor((fillRgb) | ((solid ? solidAlpha : fillAlpha) << 24));
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        float half = strokePx / 2f;
        rect.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half);
        path.reset();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        rimPaint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, RIM_TOP, RIM_BOTTOM, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.isEmpty()) {
            return;
        }
        canvas.drawPath(path, fillPaint);
        canvas.drawPath(path, rimPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        rimPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public boolean getPadding(Rect padding) {
        return false;
    }
}
