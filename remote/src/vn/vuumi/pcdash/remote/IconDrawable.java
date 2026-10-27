package vn.vuumi.pcdash.remote;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Vector-style icons drawn with a single colour, so they stay sharp and match button text. */
final class IconDrawable extends Drawable {

    enum Type { WAKE, SLEEP, HIBERNATE, SHUTDOWN, ABORT, REFRESH }

    private final Type type;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path cut = new Path();
    private final RectF oval = new RectF();

    IconDrawable(Type type, int color) {
        this.type = type;
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        if (s <= 0) return;
        float left = b.left + (b.width() - s) / 2f;
        float top = b.top + (b.height() - s) / 2f;
        stroke.setStrokeWidth(s * 0.1f);
        switch (type) {
            case WAKE:
                drawBolt(canvas, left, top, s);
                break;
            case SLEEP:
                drawZ(canvas, left, top, s);
                break;
            case HIBERNATE:
                drawMoon(canvas, left, top, s);
                break;
            case SHUTDOWN:
                drawPower(canvas, left, top, s);
                break;
            case ABORT:
                drawAbort(canvas, left, top, s);
                break;
            case REFRESH:
                drawRefresh(canvas, left, top, s);
                break;
            default:
                break;
        }
    }

    private void drawBolt(Canvas c, float l, float t, float s) {
        path.reset();
        path.moveTo(l + s * 0.58f, t + s * 0.05f);
        path.lineTo(l + s * 0.2f, t + s * 0.56f);
        path.lineTo(l + s * 0.48f, t + s * 0.56f);
        path.lineTo(l + s * 0.4f, t + s * 0.95f);
        path.lineTo(l + s * 0.8f, t + s * 0.42f);
        path.lineTo(l + s * 0.52f, t + s * 0.42f);
        path.close();
        c.drawPath(path, fill);
    }

    private void drawZ(Canvas c, float l, float t, float s) {
        // big Z
        path.reset();
        path.moveTo(l + s * 0.12f, t + s * 0.32f);
        path.lineTo(l + s * 0.62f, t + s * 0.32f);
        path.lineTo(l + s * 0.12f, t + s * 0.88f);
        path.lineTo(l + s * 0.62f, t + s * 0.88f);
        c.drawPath(path, stroke);
        // small z
        float old = stroke.getStrokeWidth();
        stroke.setStrokeWidth(old * 0.8f);
        path.reset();
        path.moveTo(l + s * 0.68f, t + s * 0.1f);
        path.lineTo(l + s * 0.92f, t + s * 0.1f);
        path.lineTo(l + s * 0.68f, t + s * 0.38f);
        path.lineTo(l + s * 0.92f, t + s * 0.38f);
        c.drawPath(path, stroke);
        stroke.setStrokeWidth(old);
    }

    private void drawMoon(Canvas c, float l, float t, float s) {
        path.reset();
        path.addCircle(l + s * 0.5f, t + s * 0.5f, s * 0.42f, Path.Direction.CW);
        cut.reset();
        cut.addCircle(l + s * 0.68f, t + s * 0.36f, s * 0.36f, Path.Direction.CW);
        path.op(cut, Path.Op.DIFFERENCE);
        c.drawPath(path, fill);
    }

    private void drawPower(Canvas c, float l, float t, float s) {
        oval.set(l + s * 0.14f, t + s * 0.16f, l + s * 0.86f, t + s * 0.88f);
        c.drawArc(oval, -60f, 300f, false, stroke);
        c.drawLine(l + s * 0.5f, t + s * 0.06f, l + s * 0.5f, t + s * 0.5f, stroke);
    }

    private void drawAbort(Canvas c, float l, float t, float s) {
        oval.set(l + s * 0.08f, t + s * 0.08f, l + s * 0.92f, t + s * 0.92f);
        c.drawOval(oval, stroke);
        c.drawLine(l + s * 0.34f, t + s * 0.34f, l + s * 0.66f, t + s * 0.66f, stroke);
        c.drawLine(l + s * 0.66f, t + s * 0.34f, l + s * 0.34f, t + s * 0.66f, stroke);
    }

    // A circular arrow, turning clockwise.
    private void drawRefresh(Canvas c, float l, float t, float s) {
        float cx = l + s * 0.5f;
        float cy = t + s * 0.5f;
        float r = s * 0.34f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        float start = 20f;
        float sweep = 280f;
        c.drawArc(oval, start, sweep, false, stroke);
        double end = Math.toRadians(start + sweep);
        float ex = cx + r * (float) Math.cos(end);
        float ey = cy + r * (float) Math.sin(end);
        // Tangent (direction of travel) and normal at the end of the arc.
        float tx = -(float) Math.sin(end);
        float ty = (float) Math.cos(end);
        float nx = (float) Math.cos(end);
        float ny = (float) Math.sin(end);
        float head = s * 0.16f;
        path.reset();
        path.moveTo(ex + tx * head, ey + ty * head);
        path.lineTo(ex + nx * head, ey + ny * head);
        path.lineTo(ex - nx * head, ey - ny * head);
        path.close();
        c.drawPath(path, fill);
    }

    @Override
    public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        stroke.setColorFilter(colorFilter);
        fill.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
