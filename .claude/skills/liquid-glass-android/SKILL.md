---
name: liquid-glass-android
description: Apply an Apple-style "Liquid Glass" look (translucent layered surfaces, rim highlights, tinted state) to native Android apps built with plain Java views, safely: API 24+ fallbacks, battery/OLED cost, legibility, accessibility. Use when the user asks for Liquid Glass, glassmorphism, blur, frosted or translucent UI on Android, or wants to restyle PCDash nav bars, sheets or dialogs with a glass feel.
---

# Liquid Glass on Android (plain Java views, minSdk 24)

Apple's Liquid Glass is a SwiftUI/AppKit material (`.glassEffect()`). Android has no such material. This skill turns its design rules into things Android views can really do. Sources at the end. The third requested source (mcpservers.org openai/liquid-glass) returned 403 and was not read.

## What Android can and cannot do

| Effect | API | Min API | Use here |
|---|---|---|---|
| Blur a view's own pixels | `View.setRenderEffect(RenderEffect.createBlurEffect(...))` | 31 | Not a backdrop blur. Blurs the view itself. |
| Blur what is behind a window (dialog, bottom sheet, popup) | `Window.setBackgroundBlurRadius()` or `LayoutParams.setBlurBehindRadius()`; window must be translucent | 31 | Best fit for sheets and dialogs. |
| Refraction (lens bend) | AGSL `RuntimeShader` plus backdrop capture | 33 | Community libs only. Skip unless asked; costly. |
| Frosted look without blur | Translucent fill + gradient rim + highlight | 21 | Works everywhere, nearly free. Default choice. |

`isCrossWindowBlurEnabled` / `WindowManager.addCrossWindowBlurEnabledListener` says whether blur is really available. It can turn off at runtime (Battery Saver, GPU limits), so listen, do not check once. Always on API < 31: false.

## Design rules (from Apple's guidance, adapted)

1. **Navigation layer only.** Glass belongs to what floats above content: bottom tab bar, bottom sheet, dialog, floating action buttons. Not list rows, not cards in the scrolling content, not charts.
2. **No glass on glass.** Never stack two translucent surfaces. A sheet over a glass bar gets a solid or scrimmed surface.
3. **Group nearby glass.** Related controls share one container shape (one pill for the tab bar, not five).
4. **Tint carries state.** Use a faint color wash for state (offline red, update green), not for decoration. Keep one corner radius family (PCDash uses 12dp cards, pill for bars).
5. **Legibility beats effect.** Text on glass needs a dimming layer (scrim 30 to 50 percent black) and contrast checked against the brightest content that can scroll under it.
6. **Fallback is a design, not an afterthought.** Provide a solid surface when blur is unavailable, in Battery Saver, or when the user wants less transparency. Apple exposes Reduce Transparency, Increase Contrast, Reduce Motion; Android has no single switch, so honor `PowerManager.isPowerSaveMode()`, `isCrossWindowBlurEnabled`, and the animator duration scale.

## Recipe for plain Java views (no blur needed)

Use `GradientDrawable` layers (see `UiKit.java` in `remote/src`):

- Fill: card color at 80 to 92 percent alpha (`Color.argb(215, 16, 20, 26)`).
- Rim: 1dp stroke with a vertical gradient of white alpha 40 at top to 8 at bottom (draw with a second drawable in a `LayerDrawable`; `GradientDrawable.setStroke` is a flat color).
- One rim only. Do not add a separate highlight line just inside the rim: on a rounded top edge it reads as a doubled border (the user caught this on the tab bar and it was removed). The fading rim gradient already gives the highlight.
- Shadow: avoid on OLED black (invisible). Use a slightly darker outer ring instead.
- Press feedback: `RippleDrawable` with white alpha 40, as in `UiKit.withRipple`.

Window blur for a dialog or sheet (API 31+, guarded):

```java
if (Build.VERSION.SDK_INT >= 31) {
    Window w = dialog.getWindow();
    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
    w.getAttributes().setBlurBehindRadius(24);
    // Window background must be translucent for the blur to show.
    w.setBackgroundDrawable(new ColorDrawable(Color.argb(150, 0, 0, 0)));
}
// Else: leave the solid scrim (alpha ~ 230) as the fallback.
```

Register `WindowManager.addCrossWindowBlurEnabledListener` and swap the alpha between the translucent and solid values.

## OLED and battery

- True black backgrounds only save power where black pixels are actually unlit. Translucent glass over black shows nothing to blur, so the effect would be invisible. Use glass where content scrolls under it (tab bar over lists, sheets over the screen), and keep the page background `#000`.
- Blur cost scales with area and with how often the backdrop changes. One large blurred panel costs more than several small ones; a blurred bar above a scrolling list is the worst case. Prefer small, static glass (sheet, dialog) over a live blurred bar.
- Android reports Battery Saver removing blur on some builds; no measured numbers were found. Measure with `scripts\perf-smoke-remote.ps1` before and after.
- Never add refraction or shaders to a screen that polls every few seconds (PCDash Monitor, Quota) unless measured.

## Sliding glass pill (tab switch animation)

Implemented in `remote/src/vn/vuumi/pcdash/remote/BottomNavBar.java`. One `View` pill sits behind the tab row inside a `FrameLayout`, and slides to the selected tab instead of switching instantly.

- One pill, not a background per item. Position it with `translationX = row.getLeft() + item.getLeft()` and `translationY = row.getTop() + item.getTop()`; its size is the item size.
- First placement and relayouts (insets, rotation) snap without animation; only a real tab change animates. Guard `item.getWidth() == 0` and snap from `row.addOnLayoutChangeListener`.
- A single `ValueAnimator` 0 to 1, 420 ms, position eased with `OvershootInterpolator(0.9f)`. Liquid stretch: `bump = sin(pi * f)`, `reach = min(1, distance / (2 * itemWidth))`, `scaleX = 1 + 0.4 * bump * reach`, `scaleY = 1 - 0.12 * bump * reach`. Short hops stretch less than long ones.
- On cancel or end reset the scales and snap to the final slot. `cancel()` also fires `onAnimationEnd`, so keep the end handler idempotent.
- `ValueAnimator` honors the system animator duration scale, so "remove animations" gives an instant switch with no extra code.
- Content follows the pill: in `RemoteMainActivity.switchTab` the incoming view fades in and slides 28 dp from the direction of travel (compare `MainTab.ordinal()`), 240 ms, `DecelerateInterpolator(1.6f)`, with `.withLayer()` so the heavy scroll views are composited once. First show (no previous tab) does not animate.
- Known small gap: the label colors flip instantly while the pill is still travelling.

Measured on the Honor with `perf-smoke-remote.ps1` after adding it: 4577 frames, 0.26 percent janky, p99 10 ms, 0 ANR.

## Applying to PCDash (done)

1. Bottom tab bar: translucent card fill, single fading rim, no blur, pure black behind, sliding selected pill.
2. "Thêm" bottom sheet and the confirm AlertDialogs: window blur on API 31+ with an opaque fallback (`GlassKit.applyDialogBlur`, `GlassKit.styleAlert`). Verified by screenshot that the page behind the sheet is blurred; the AlertDialogs are not screenshot-verified because they sit behind power buttons the walkthrough never presses.
3. Update card tint by state (green available, orange permission, blue downloading, red error).

Files: `GlassDrawable.java`, `GlassKit.java`, `UiKit.java`, `IconDrawable.java`, `BottomNavBar.java`.

Skip: glass on Monitor/Quota/Log cards (content layer), blur behind charts, any shader. The host app has no tab bar or sheet; its dialogs are not glass yet.

After changes, run the `pcdash-review` skill: walkthrough screenshots, a short screen recording for animations, perf smoke, and a contrast check of muted text on the glass.

## Sources

- Apple WWDC25 notes, Meet Liquid Glass: https://wwdcnotes.com/documentation/wwdc25-219-meet-liquid-glass/
- Apple Developer Forums, WWDC25 design lab summary: https://developer.apple.com/forums/thread/791070
- Liquid Glass best practices (navigation layer, no glass on glass): https://dev.to/diskcleankit/liquid-glass-in-swift-official-best-practices-for-ios-26-macos-tahoe-1coo
- Accessibility behavior (Reduce Transparency): https://tidbits.com/2025/10/09/how-to-turn-liquid-glass-into-a-solid-interface/
- AOSP window blurs: https://source.android.com/docs/core/display/window-blurs
- Android `RenderEffect` (API 31): https://developer.android.com/reference/android/graphics/RenderEffect
- Android refraction with AGSL (API 33, fallback below): https://dev.to/wrack/liquid-glass-on-android-in-react-native-i-refracted-the-backdrop-with-a-real-time-agsl-shader-1dp4
- Compose libraries: Haze https://github.com/chrisbanes/haze, AndroidLiquidGlass (Kyant), KMPLiquidGlass https://github.com/Kashif-E/KMPLiquidGlass
- SwiftUI skill (rshankras): https://lobehub.com/skills/rshankras-claude-code-apple-skills-liquid-glass
- Web refraction module (deepika-builds): https://github.com/deepika-builds/liquid-glass/blob/main/SKILL.md
