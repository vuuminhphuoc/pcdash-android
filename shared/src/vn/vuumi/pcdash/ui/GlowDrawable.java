package vn.vuumi.pcdash.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

/**
 * A foreground that lights a button up where the finger touches it: a soft glow spreads from the touch
 * point and the rim brightens, then both fade when the finger lifts. While the button's action runs,
 * {@link #setBreathing} makes the rim glow slowly in and out. The view passes the touch point through
 * {@link #setHotspot} and the pressed state through {@link #onStateChange}.
 */
public final class GlowDrawable extends Drawable {

    private final float cornerPx;
    private final float rimPx;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RadialGradient gradient;
    private final Matrix matrix = new Matrix();
    private final Path clip = new Path();
    private final RectF rect = new RectF();

    private float hotX = -1f;
    private float hotY = -1f;
    private float strength; // 0..1, how lit the press glow is
    private float spread;   // 0..1, how far the glow has spread
    private float breath;   // 0..1, rim glow while busy
    private boolean pressed;
    private ValueAnimator strengthAnim;
    private ValueAnimator spreadAnim;
    private ValueAnimator breathAnim;

    /**
     * @param color    the light's colour (usually a lighter shade of the button)
     * @param cornerPx the button's corner radius
     * @param rimPx    width of the lit rim
     */
    public GlowDrawable(int color, float cornerPx, float rimPx) {
        this.cornerPx = cornerPx;
        this.rimPx = rimPx;
        // Unit-radius gradient at the origin; moved and scaled with a matrix on each frame.
        gradient = new RadialGradient(0f, 0f, 1f,
                new int[]{Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        glow.setShader(gradient);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimPx);
        rim.setColor(color);
    }

    /** Lighter shade of a button colour, for the glow. */
    public static int lightOf(int rgb) {
        int r = Color.red(rgb);
        int g = Color.green(rgb);
        int b = Color.blue(rgb);
        return Color.rgb(r + (255 - r) * 55 / 100, g + (255 - g) * 55 / 100, b + (255 - b) * 55 / 100);
    }

    /** Slow rim glow while the button's action runs. */
    public void setBreathing(boolean on) {
        if (on) {
            if (breathAnim != null && breathAnim.isStarted()) return;
            breathAnim = ValueAnimator.ofFloat(0.15f, 1f);
            breathAnim.setDuration(900);
            breathAnim.setRepeatMode(ValueAnimator.REVERSE);
            breathAnim.setRepeatCount(ValueAnimator.INFINITE);
            breathAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    breath = (Float) a.getAnimatedValue();
                    invalidateSelf();
                }
            });
            breathAnim.start();
        } else if (breathAnim != null) {
            breathAnim.cancel();
            breathAnim = null;
            breath = 0f;
            invalidateSelf();
        }
    }

    @Override
    public void setHotspot(float x, float y) {
        hotX = x;
        hotY = y;
        if (strength > 0f) invalidateSelf();
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean isPressed = false;
        boolean enabled = false;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) isPressed = true;
            if (s == android.R.attr.state_enabled) enabled = true;
        }
        isPressed = isPressed && enabled;
        if (isPressed == pressed) return false;
        pressed = isPressed;
        if (pressed) {
            animateStrength(1f, 120);
            spread = 0f;
            animateSpread(520);
        } else {
            animateStrength(0f, 480);
        }
        return true;
    }

    @Override
    public void jumpToCurrentState() {
        if (strengthAnim != null) strengthAnim.cancel();
        if (spreadAnim != null) spreadAnim.cancel();
        strength = pressed ? 1f : 0f;
        spread = pressed ? 1f : 0f;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        if (strength <= 0.01f && breath <= 0.01f) return;
        Rect b = getBounds();
        rect.set(b);
        int save = canvas.save();
        clip.reset();
        clip.addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW);
        canvas.clipPath(clip);
        if (strength > 0.01f) {
            float cx = hotX >= 0f ? hotX : b.exactCenterX();
            float cy = hotY >= 0f ? hotY : b.exactCenterY();
            float radius = Math.max(b.width(), b.height()) * (0.25f + 0.85f * spread);
            matrix.setScale(radius, radius);
            matrix.postTranslate(cx, cy);
            gradient.setLocalMatrix(matrix);
            glow.setAlpha((int) (255 * strength));
            canvas.drawRect(rect, glow);
        }
        float rimLight = Math.max(strength * 0.9f, breath * 0.75f);
        if (rimLight > 0.01f) {
            rim.setAlpha((int) (255 * rimLight));
            float inset = rimPx / 2f;
            rect.inset(inset, inset);
            canvas.drawRoundRect(rect, Math.max(0f, cornerPx - inset), Math.max(0f, cornerPx - inset), rim);
        }
        canvas.restoreToCount(save);
    }

    private void animateStrength(float to, long ms) {
        if (strengthAnim != null) strengthAnim.cancel();
        strengthAnim = ValueAnimator.ofFloat(strength, to);
        strengthAnim.setDuration(ms);
        strengthAnim.setInterpolator(new DecelerateInterpolator());
        strengthAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                strength = (Float) a.getAnimatedValue();
                invalidateSelf();
            }
        });
        strengthAnim.start();
    }

    private void animateSpread(long ms) {
        if (spreadAnim != null) spreadAnim.cancel();
        spreadAnim = ValueAnimator.ofFloat(spread, 1f);
        spreadAnim.setDuration(ms);
        spreadAnim.setInterpolator(new DecelerateInterpolator(1.8f));
        spreadAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                spread = (Float) a.getAnimatedValue();
                invalidateSelf();
            }
        });
        spreadAnim.start();
    }

    @Override
    public void setAlpha(int alpha) {
        // Driven by the press and breathing animations.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        glow.setColorFilter(colorFilter);
        rim.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
