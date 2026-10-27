---
name: pcdash-review
description: Check performance (ANR, memory, jank), UX and UI of the PCDash host and remote Android apps by walking every screen on the real phones over adb, then writing a prioritized review. Use when the user says "check performance", "review UX/UI", "đi qua tất cả giao diện", "kiểm tra hiệu năng/ANR", "review giao diện", or after a UI change that needs on-device verification.
---

# PCDash performance + UX/UI review

Real phones, real screenshots. Never judge a UI from code alone.

## Safety (hard rules)

- Walkthrough is read-only. Never tap Wake, Sleep, Hibernate, Shutdown, Hủy lệnh tắt, Xóa thiết bị, Xóa nhật ký, Quên host, Thêm thiết bị, or enter PIN/passwords. `scripts\ux-walkthrough.ps1` refuses these by name.
- User does system/security settings (Private DNS, ACL, install-unknown-apps, VPN toggles).
- Do not commit or push unless asked.

## 1. Devices

```powershell
adb devices -l
```

- Host phone: pass its adb serial with `-HostSerial` or `$env:PCDASH_HOST_SERIAL` (wireless-debugging `ip:port` or mDNS name from `adb devices -l`).
- Remote phone: pass its adb serial with `-Remote` or `$env:PCDASH_REMOTE_SERIAL`. Wireless adb devices can disappear when away from the home Wi-Fi.
- If a device is missing, say so and review only the other one. If more than one device could match, ask which.

## 2. Performance and ANR

```powershell
pwsh -File scripts\perf-smoke-remote.ps1          # 30 tab-switch rounds: gfxinfo, logcat ANR/crash, threads, PSS
pwsh -File scripts\lint-ui-thread.ps1             # static: no blocking I/O or monitors on UI thread
pwsh -File scripts\test-remote-java.ps1           # includes UiThreadSafetyTest
```

Baselines (last measured after the glass tab bar, on the Honor): 0 ANR, 0 crash, jank 0.26 to 5 percent, p99 10 to 17 ms; host about 32 MB PSS; remote about 125 MB PSS at rest (150 MB right after 30 tab rounds). Flag regressions.

How to read the PSS number:

- `dumpsys meminfo vn.vuumi.pcdash.remote` shows where it goes. Last breakdown: native heap about 38 MB (embedded tsnet), EGL mtrack about 28 MB (display buffers at 1256x2808), `.so` mmap about 24 MB, Dalvik heap only about 12 MB. The Java views are not the cost.
- The script's "PSS increased by more than 40 MB" check trips on a cold app because tsnet and the charts warm up. Open the app, switch through every tab once, wait about 30 s, then run the smoke test. On a warm app the delta is about 23 MB and passes.
- The script now fails fast if gfxinfo reports 0 frames (the tab taps did not reach the app); a 4950 ms p99 with 0 frames is a placeholder, not a real result.
- `perf-smoke-remote.ps1` sets UTF-8 console encoding (Vietnamese tab labels) and falls back to proportional tap coordinates when uiautomator cannot find all 5 tabs.

Also check on device:

- `adb -s <serial> logcat -d -b crash` and `grep -i "ANR in"` for the two packages `vn.vuumi.pcdash` and `vn.vuumi.pcdash.remote`.
- StrictMode lines (`StrictMode`) in logcat for the debug policy.
- Any synchronized controller method held during network I/O (root cause of the earlier ANR). `session()` snapshot plus lock-free reads is the pattern.

## 3. Walk every screen

```powershell
pwsh -File scripts\ux-walkthrough.ps1             # saves PNGs to remote\build\ux-walkthrough
```

Captures: remote tabs (Điều khiển, Giám sát with scroll, Quota with scroll and detail, Nhật ký, Thêm sheet) and host (main with scroll, Thiết bị điều khiển từ xa). Read each PNG with the Read tool. Screenshots are large; when the app is already on a tab, read only what you need.

Tab bar tap row on the Honor is y about 0.973 of height, x at 0.148, 0.323, 0.499, 0.675, 0.852 of width.

### Animations: record, do not guess

A screenshot cannot show a transition. Record the screen, then cut frames:

```bash
export MSYS_NO_PATHCONV=1                           # Git Bash otherwise rewrites /sdcard paths
(adb -s <serial> shell screenrecord --time-limit 6 --size 628x1404 --bit-rate 6000000 /sdcard/tab.mp4 &)
sleep 1.5; adb -s <serial> shell input tap <tabX> <tabY>   # one tap per animation under test
sleep 3; adb -s <serial> pull /sdcard/tab.mp4 remote/build/ux-walkthrough/tab.mp4
ffmpeg -loglevel error -y -ss <t> -t 1.2 -i tab.mp4 -vf "fps=8,crop=628:210:0:1194,tile=2x5" -frames:v 1 montage.png
```

`ffmpeg` is at `C:\ffmpeg\bin`. Read `montage.png`: you should see start, a mid-flight frame, overshoot and the settled frame. Crop to the area that moves (the tab bar is the bottom 210 px at that size). Check that the end state is clean (no leftover stretch, one border).

### Windows tooling gotchas

- PowerShell function names are case-insensitive: a helper called `Adb` that runs `& adb` calls itself forever ("call depth overflow"). Name helpers `Invoke-Adb` and run `adb.exe`.
- Vietnamese text matching through uiautomator needs `[Console]::OutputEncoding = UTF8` and scripts saved with a BOM.
- Never run two builds at once, and do not tap power controls during walkthroughs.

## 4. Review checklist

Judge each screen against these, with a concrete screenshot observation per finding:

1. **Meaning**: every number has a unit and a clear sense (remaining vs used, which device, which sensor). No jargon like ACPI or node.
2. **Truth**: no stale instructions (old VPN, tun IPs), no row that lies (status for the wrong device), no legend for a series with no data.
3. **Hierarchy**: one primary action per screen; destructive actions separated, quieter than safe ones, behind a confirmation.
4. **Consistency**: host and remote share card style, radius, colors, nav bar color; no white system bar on a dark theme.
5. **Density**: no long unbroken lists (collapse identical rows), no dead half-screens.
6. **Safe areas**: nothing clipped by rounded corners, status bar or gesture bar; touch targets at least 48dp.
7. **States**: loading, empty, error, offline, stale each look different and say what to do next.
8. **Text**: Vietnamese diacritics correct, no mixed languages in one label, no emoji standing in for icons where alignment matters.
9. **Dark theme only, OLED**: window, status bar, nav bar and action bar pure black (#000); cards at most rgb(16,20,26) with a 1dp border; no large bright fills (use darker saturated or outlined buttons); contrast of muted text on cards is readable at arm length.

## 5. Output

Write findings in Vietnamese, terse, grouped:

- Lỗi / gây hiểu nhầm (fix first)
- Bố cục / thẩm mỹ
- Điểm tốt, giữ nguyên

Each finding: screen, what is seen, why it matters, suggested fix. End with performance numbers vs baseline. Offer to fix; if asked to fix, edit, run tests, bump `remote/version.properties` and `app/version.properties`, run `build-all.ps1`, install on both phones, and re-run the walkthrough to confirm.

## Build and install (when fixing)

```powershell
pwsh -File build-all.ps1                           # remote first, embeds remote APK into host assets, then host
adb -s <serial> install -r remote\build\pcdash-remote-signed.apk
adb -s <serial> install -r app\build\<host apk>    # path printed by build-all
```

Do not run two builds at once. Delegated builder agents run host-only `build.ps1`; run `build-all.ps1` only after they finish.

Bump both `version.properties` files on every UI change so `install -r` and the host's self-update path see a new version. Current: remote 1.23 (24), host 1.21 (22).

Committing on this machine: git has no `user.name` or `user.email`. Reuse the repo's existing author for that one commit instead of changing config: `git -c user.name="<name>" -c user.email="<email>" commit ...` (see `git log -1 --format=%an,%ae`). Commit and push only when the user asks.
