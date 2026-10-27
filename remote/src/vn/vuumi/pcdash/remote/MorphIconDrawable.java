package vn.vuumi.pcdash.remote;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * A power action's icon that changes shape with its command: the icon shrinks away into a spinning arc
 * while the command runs, the arc closes and a tick draws itself in (with a ring that spreads out) or a
 * cross draws in with a short shake, and then the icon springs back. All in one drawable, animated with
 * the platform animators, so the animator duration scale (and "Remove animations") applies.
 */
final class MorphIconDrawable extends Drawable {

    private final IconDrawable icon;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path mark = new Path();
    private final Path segment = new Path();
    private final PathMeasure measure = new PathMeasure();

    // What is drawn, between 0 and 1 unless noted.
    private float iconScale = 1f;
    private float iconAlpha = 1f;
    private float spinnerAlpha;
    private float spinnerRotation; // degrees
    private float spinnerSweep = 90f; // degrees
    private float markProgress;
    private float markAlpha;
    private boolean markIsCheck = true;
    private float ring = -1f; // < 0: no ring
    private float shake;

    private ValueAnimator transition;
    private ValueAnimator spin;
    private ValueAnimator ringAnim;
    private ValueAnimator shakeAnim;

    MorphIconDrawable(IconDrawable.Type type, int color) {
        icon = new IconDrawable(type, color);
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    /** The icon folds into a spinning arc. */
    void toBusy() {
        startSpin();
        final float fromScale = iconScale;
        final float fromIcon = iconAlpha;
        final float fromMark = markAlpha;
        final float fromSpinner = spinnerAlpha;
        run(260, new DecelerateInterpolator(), new Step() {
            @Override
            public void at(float p) {
                iconScale = lerp(fromScale, 0.3f, p);
                iconAlpha = lerp(fromIcon, 0f, p);
                markAlpha = lerp(fromMark, 0f, p);
                spinnerAlpha = lerp(fromSpinner, 1f, p);
            }
        }, null);
    }

    /** The arc gives way to a tick (with a spreading ring) or a cross (with a shake). */
    void toResult(final boolean ok) {
        markIsCheck = ok;
        buildMark();
        final float fromSpinner = spinnerAlpha;
        final float fromIcon = iconAlpha;
        run(420, new OvershootInterpolator(1.4f), new Step() {
            @Override
            public void at(float p) {
                float q = Math.min(1f, Math.max(0f, p));
                spinnerAlpha = lerp(fromSpinner, 0f, Math.min(1f, q * 2.5f));
                iconAlpha = lerp(fromIcon, 0f, q);
                markAlpha = 1f;
                markProgress = q;
                iconScale = 0.3f;
            }
        }, new Runnable() {
            @Override
            public void run() {
                stopSpin();
            }
        });
        if (ok) {
            startRing();
        } else {
            startShake();
        }
    }

    /** Back to the plain icon, with a small spring. */
    void toIdle() {
        stopSpin();
        final float fromMark = markAlpha;
        final float fromSpinner = spinnerAlpha;
        final float fromScale = iconScale;
        final float fromIcon = iconAlpha;
        run(380, new OvershootInterpolator(2f), new Step() {
            @Override
            public void at(float p) {
                float q = Math.min(1f, Math.max(0f, p));
                markAlpha = lerp(fromMark, 0f, Math.min(1f, q * 2f));
                spinnerAlpha = lerp(fromSpinner, 0f, q);
                iconAlpha = lerp(fromIcon, 1f, q);
                iconScale = lerp(fromScale, 1f, p); // may overshoot a little: the spring
            }
        }, null);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        if (s <= 0) return;
        float cx = b.exactCenterX() + shake * s * 0.12f;
        float cy = b.exactCenterY();
        stroke.setStrokeWidth(s * 0.11f);

        if (ring >= 0f) {
            int a = (int) (200 * (1f - ring));
            if (a > 0) {
                float w = stroke.getStrokeWidth();
                stroke.setStrokeWidth(s * 0.07f * (1f - ring) + 1f);
                stroke.setAlpha(a);
                canvas.drawCircle(cx, cy, s * (0.45f + 0.55f * ring), stroke);
                stroke.setStrokeWidth(w);
            }
        }

        if (iconAlpha > 0.01f) {
            int save = canvas.save();
            canvas.translate(cx - b.exactCenterX(), 0f);
            canvas.scale(iconScale, iconScale, b.exactCenterX(), b.exactCenterY());
            icon.setBounds(b);
            icon.setAlpha((int) (255 * Math.min(1f, iconAlpha)));
            icon.draw(canvas);
            canvas.restoreToCount(save);
        }

        if (spinnerAlpha > 0.01f) {
            float r = s * 0.38f;
            oval.set(cx - r, cy - r, cx + r, cy + r);
            stroke.setAlpha((int) (255 * Math.min(1f, spinnerAlpha)));
            canvas.drawArc(oval, spinnerRotation, spinnerSweep, false, stroke);
        }

        if (markAlpha > 0.01f && markProgress > 0f) {
            stroke.setAlpha((int) (255 * Math.min(1f, markAlpha)));
            int save = canvas.save();
            canvas.translate(cx - s / 2f, cy - s / 2f);
            drawTrimmed(canvas, Math.min(1f, markProgress));
            canvas.restoreToCount(save);
        }
    }

    // The mark in a unit box of the current size; drawn up to the given fraction of its length.
    private void buildMark() {
        float s = Math.max(1, Math.min(getBounds().width(), getBounds().height()));
        mark.reset();
        if (markIsCheck) {
            mark.moveTo(s * 0.2f, s * 0.53f);
            mark.lineTo(s * 0.42f, s * 0.74f);
            mark.lineTo(s * 0.8f, s * 0.3f);
        } else {
            mark.moveTo(s * 0.28f, s * 0.28f);
            mark.lineTo(s * 0.72f, s * 0.72f);
            mark.moveTo(s * 0.72f, s * 0.28f);
            mark.lineTo(s * 0.28f, s * 0.72f);
        }
    }

    private void drawTrimmed(Canvas canvas, float fraction) {
        measure.setPath(mark, false);
        int contours = 0;
        do {
            contours++;
        } while (measure.nextContour());
        // Each stroke of the mark gets an equal share of the time, one after the other.
        measure.setPath(mark, false);
        int index = 0;
        do {
            float start = (float) index / contours;
            float local = Math.min(1f, Math.max(0f, (fraction - start) * contours));
            if (local > 0f) {
                segment.reset();
                measure.getSegment(0f, measure.getLength() * local, segment, true);
                canvas.drawPath(segment, stroke);
            }
            index++;
        } while (measure.nextContour());
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        buildMark();
    }

    private interface Step {
        void at(float p);
    }

    private void run(long ms, android.animation.TimeInterpolator interpolator, final Step step, final Runnable end) {
        if (transition != null) transition.cancel();
        transition = ValueAnimator.ofFloat(0f, 1f);
        transition.setDuration(ms);
        transition.setInterpolator(interpolator);
        transition.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                step.at((Float) a.getAnimatedValue());
                invalidateSelf();
            }
        });
        if (end != null) {
            transition.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    end.run();
                }
            });
        }
        transition.start();
    }

    private void startSpin() {
        if (spin != null && spin.isStarted()) return;
        spin = ValueAnimator.ofFloat(0f, 1f);
        spin.setDuration(1400);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                // Turns and breathes: the arc grows and shrinks while it goes round.
                spinnerRotation = (t * 720f) % 360f; // two turns per cycle, the same at any frame rate
                spinnerSweep = 40f + 230f * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * t));
                invalidateSelf();
            }
        });
        spin.start();
    }

    private void stopSpin() {
        if (spin != null) {
            spin.cancel();
            spin = null;
        }
    }

    private void startRing() {
        if (ringAnim != null) ringAnim.cancel();
        ringAnim = ValueAnimator.ofFloat(0f, 1f);
        ringAnim.setDuration(650);
        ringAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        ringAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                ring = (Float) a.getAnimatedValue();
                invalidateSelf();
            }
        });
        ringAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                ring = -1f;
                invalidateSelf();
            }
        });
        ringAnim.start();
    }

    private void startShake() {
        if (shakeAnim != null) shakeAnim.cancel();
        shakeAnim = ValueAnimator.ofFloat(0f, 1f);
        shakeAnim.setDuration(420);
        shakeAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float p = (Float) a.getAnimatedValue();
                shake = (float) Math.sin(p * 6 * Math.PI) * (1f - p);
                invalidateSelf();
            }
        });
        shakeAnim.start();
    }

    /** Stops every animation (the tile left the screen). */
    void stop() {
        stopSpin();
        if (transition != null) transition.cancel();
        if (ringAnim != null) ringAnim.cancel();
        if (shakeAnim != null) shakeAnim.cancel();
    }

    private static float lerp(float a, float b, float p) {
        return a + (b - a) * p;
    }

    @Override
    public void setAlpha(int alpha) {
        // Alpha is driven by the animation itself.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        stroke.setColorFilter(colorFilter);
        icon.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
