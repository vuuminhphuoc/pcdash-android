# Kiểm tra PCDash trên thiết bị thật

Tài liệu này dành cho một phiên Claude Code chạy trên máy Windows của bạn, có cắm (hoặc adb không dây) **điện thoại ở nhà (host)** và **điện thoại điều khiển (remote)**. AI làm theo từng mục bên dưới, tự chạy được phần lớn qua adb, chụp màn hình hoặc quay video làm bằng chứng, và hỏi bạn ở những bước tác động lên PC hoặc cần tay người.

Kiểm tra toàn bộ những gì có trên nhánh `ccr-ee4899f2-z93fx1`: build Gradle, Kotlin, tab Điều khiển viết bằng Compose, nhiều PC, ghép đôi QR, khóa ứng dụng, cảnh báo, nút bấm, kết nối lại, ngôn ngữ, ô Cài đặt nhanh.

Phần đi qua màn hình, đo hiệu năng và tiêu chí đánh giá giao diện nằm trong skill [`pcdash-review`](../.claude/skills/pcdash-review/SKILL.md); tài liệu này dùng lại các script của skill đó và thêm phần kiểm tra chức năng.

## Bắt đầu phiên

Dán vào phiên Claude Code local (ở thư mục repo):

```text
Đọc docs/device-check.md và .claude/skills/pcdash-review/SKILL.md, rồi kiểm tra toàn bộ trên 2 điện thoại đang nối adb theo đúng thứ tự trong tài liệu. Hỏi tôi trước mỗi bước có ⚠️, nhờ tôi làm các bước có 👤. Lưu ảnh, video và báo cáo vào remote\build\device-check\. Không commit, không push.
```

## Quy tắc cho AI

- ⚠️ **Hỏi trước rồi mới làm**: mọi nút nguồn (Bật/Đánh thức, Sleep, Hibernate, Tắt máy, Hủy lệnh tắt máy), xóa hoặc quên thiết bị, thêm hoặc xóa PC trong Cài đặt PC, gỡ cài đặt app, đổi cài đặt hệ thống (tốc độ hiệu ứng, ngôn ngữ app, chế độ máy bay). Nói rõ sẽ làm gì và ảnh hưởng gì (ví dụ "PC sẽ ngủ"), chờ người dùng đồng ý.
- 👤 **Việc của người dùng**: quét QR bằng camera, vân tay hoặc khuôn mặt, tắt Wi-Fi hoặc màn hình của điện thoại, nhìn PC. AI hướng dẫn, chờ người dùng báo xong, rồi tự xác nhận bằng ảnh chụp, `uiautomator dump` hoặc logcat.
- Không tự nhập mã khóa thật của người dùng. Khi cần thử khóa ứng dụng, hỏi người dùng có cho dùng mã thử `135790` không, và tắt khóa khi xong.
- Không gỡ app để cài đè (mất ghép đôi, mã, cài đặt) nếu chưa hỏi.
- Mỗi kết luận phải có bằng chứng: đường dẫn ảnh hoặc video, dòng logcat, hoặc đoạn `uiautomator dump`. Không đoán hiệu ứng từ một ảnh tĩnh; quay video (mục 4).
- Ghi lại mọi cài đặt đã đổi để khôi phục ở mục 8.

## 1. Chuẩn bị

Công cụ: JDK 17, Android SDK (platform 36, build-tools 36.0.0), NDK `27.1.12297006`, PowerShell 7, adb, Go cài bằng script. ffmpeg (tùy chọn) để cắt khung hình từ video.

```powershell
git fetch origin
git checkout ccr-ee4899f2-z93fx1
git pull
.\scripts\install-go-toolchain.ps1 -Version 1.27.1
adb devices -l
$env:PCDASH_HOST_SERIAL = "<serial máy ở nhà>"
$env:PCDASH_REMOTE_SERIAL = "<serial máy điều khiển>"
New-Item -ItemType Directory -Force remote\build\device-check | Out-Null
```

Ghi phiên bản đang cài trước khi cài đè:

```powershell
adb -s $env:PCDASH_REMOTE_SERIAL shell dumpsys package vn.vuumi.pcdash.remote | Select-String "versionName|versionCode"
adb -s $env:PCDASH_HOST_SERIAL shell dumpsys package vn.vuumi.pcdash | Select-String "versionName|versionCode"
```

Bản trên nhánh này: remote **1.27 (28)**, host **1.27 (28)**.

## 2. Build và test tại máy

```powershell
.\build-all.ps1
```

Mong đợi:

- Lần đầu Gradle tải Gradle 8.13, Android Gradle plugin và thư viện (vài trăm MB, vài phút).
- Cuối log có `Remote APK verified`, `Host APK verified ... (bundles remote 1.26, code 27)` và `BUILD-ALL SUCCESSFUL`.
- Ra hai file `remote\build\pcdash-remote-signed.apk` và `app\build\pcdash-signed.apk`.

Chạy các test như CI:

```powershell
$tests = 'test-build-toolchain','lint-ui-thread','lint-l10n','test-host-api','test-host-pairing','test-host-ui-model','test-host-update','test-host-metrics','test-host-log-history','test-host-provisioning','test-host-quota','test-host-pcs','test-remote-java','test-pairing-e2e','test-qr-roundtrip','test-agent-metrics'
foreach ($t in $tests) { pwsh -NoProfile -File "scripts\$t.ps1"; if ($LASTEXITCODE) { Write-Host "FAIL $t" -ForegroundColor Red } }
```

Ghi vào báo cáo: thời gian build, kích thước hai APK, test nào lỗi (kèm log).

Tùy chọn: mở thư mục gốc bằng Android Studio. Project phải sync được, và `remote` phải chạy được bằng nút Run.

## 3. Cài lên điện thoại

```powershell
adb -s $env:PCDASH_REMOTE_SERIAL install -r remote\build\pcdash-remote-signed.apk
adb -s $env:PCDASH_HOST_SERIAL install -r app\build\pcdash-signed.apk
adb -s $env:PCDASH_REMOTE_SERIAL shell am start -n vn.vuumi.pcdash.remote/.RemoteMainActivity
adb -s $env:PCDASH_HOST_SERIAL shell am start -n vn.vuumi.pcdash/.MainActivity
```

- APK ký bằng `app\pcdash.keystore` và `remote\pcdash-remote.keystore`. Nếu chưa có, build sẽ tự tạo; đừng xóa hai file này.
- Nếu gặp `INSTALL_FAILED_UPDATE_INCOMPATIBLE` thì bản đang cài được ký bằng khóa khác (bản CI hoặc máy khác). Dừng lại và báo người dùng. ⚠️ Chỉ gỡ cài đặt khi người dùng đồng ý, vì gỡ sẽ mất ghép đôi và cài đặt.
- Sau khi cài: `adb logcat -b crash -d` không được có dòng nào của `vn.vuumi.pcdash`.

## 4. Hiệu năng, crash và bằng chứng hiệu ứng

Làm theo mục 2 và 3 của skill `pcdash-review`:

```powershell
pwsh -File scripts\perf-smoke-remote.ps1
pwsh -File scripts\ux-walkthrough.ps1
adb -s $env:PCDASH_REMOTE_SERIAL logcat -d | Select-String "ANR in|FATAL|StrictMode|ClassNotFound|NoSuchMethod|NoSuchField"
adb -s $env:PCDASH_REMOTE_SERIAL shell dumpsys meminfo vn.vuumi.pcdash.remote
```

- Mốc cũ: 0 ANR, 0 crash, jank 0,26–5%, p99 10–17 ms, remote khoảng 125 MB PSS lúc nghỉ. Tab Điều khiển giờ dùng Compose, nên ghi số mới và so với mốc cũ.
- Bản release của remote chạy qua R8. `ClassNotFound`, `NoSuchMethod` hoặc lỗi JNI trong logcat là dấu hiệu R8 xóa nhầm. Báo ngay, kèm stack trace.

Quay video để kiểm tra hiệu ứng (một thao tác mỗi video):

```powershell
$r = $env:PCDASH_REMOTE_SERIAL
Start-Job { adb -s $using:r shell screenrecord --time-limit 8 /sdcard/fx.mp4 } | Out-Null
Start-Sleep 1.5
# ... thao tác cần quay ...
Start-Sleep 7; adb -s $r pull /sdcard/fx.mp4 remote\build\device-check\<tên>.mp4
ffmpeg -loglevel error -y -i remote\build\device-check\<tên>.mp4 -vf "fps=6,scale=360:-1,tile=4x3" -frames:v 1 remote\build\device-check\<tên>-frames.png
```

Đọc ảnh `-frames.png`: phải thấy trạng thái đầu, khung giữa và trạng thái cuối gọn gàng.

Tìm tọa độ và nhãn trên màn hình bằng `uiautomator dump`. Compose cũng xuất ra cây này: ô nút nguồn có `content-desc` là nhãn của nó, ví dụ `Bật / Đánh thức PC` hoặc `Bật / Đánh thức PC: Đang gửi...`; nút làm mới có `content-desc="Làm mới"`:

```powershell
adb -s $r shell uiautomator dump /sdcard/ui.xml; adb -s $r pull /sdcard/ui.xml remote\build\device-check\ui.xml
```

## 5. Kiểm tra chức năng trên remote

Đánh dấu từng mục là PASS, FAIL hoặc SKIP (kèm lý do).

### 5.1 Tab Điều khiển (Compose)

1. **Thẻ trạng thái.** PC bật: chấm xanh thỉnh thoảng tỏa vòng, chữ "PC đang BẬT", dòng "Cổng · Độ trễ". Đang kết nối: chấm vàng tỏa nhanh. PC tắt hoặc mất kết nối: chấm đỏ đứng yên. Khi trạng thái đổi, chữ mờ dần sang chữ mới, không nhảy.
2. **Nút làm mới.** Bấm: icon quay đến khi có trạng thái mới, rồi quay nốt vòng chứ không giật về 0. Bấm liên tục 5 lần trong 1 giây thì chỉ một lần làm mới. Quay video cả hai.
3. **Ánh sáng khi chạm**, thử trên nút làm mới (an toàn): `adb shell input swipe x y x y 600` (giữ 0,6 s). Trong video phải thấy vầng sáng lan từ chỗ chạm, viền sáng lên, nút hơi thu nhỏ, rồi tắt dần khi nhả.
4. ⚠️ **Một lệnh trọn vẹn.** Chọn lệnh ít ảnh hưởng nhất mà người dùng đồng ý: "Hủy lệnh tắt máy" (không làm gì nếu không có lệnh tắt đang chờ), hoặc "Bật / Đánh thức PC" khi PC đang tắt. Quay video. Mong đợi:
   - icon thu lại thành vòng quay, chữ "Đang gửi...", các ô khác mờ đi;
   - với lệnh Bật: "Đang chờ..." và dòng "Đã gửi lệnh bật. Đang chờ PC khởi động..." dưới thẻ trạng thái, viền ô "thở" chậm;
   - kết quả: dấu tích kèm vòng lan và chữ "Xong" (Hủy lệnh tắt máy) hoặc "PC đã bật" (Bật), điện thoại rung nhẹ, các ô khác sáng lại. Nếu sau 2 phút PC vẫn chưa bật: dấu X, chữ "Chưa thấy đổi" và dòng vàng gợi ý kiểm tra Wake-on-LAN;
   - sau khoảng 2,5 s ô trở về icon cũ, có nảy nhẹ. Dòng kết quả dưới thẻ trạng thái tự ẩn sau khoảng 10 s.
5. ⚠️ **Lệnh lỗi.** 👤 Người dùng bật chế độ máy bay trên remote rồi bấm một lệnh, ví dụ Hủy lệnh tắt máy. Mong đợi: dấu X kèm rung lắc, chữ "Lỗi", dòng đỏ "Không gửi được lệnh: ...". Sau đó tắt chế độ máy bay.
6. ⚠️ **Chống bấm đúp.** Chạm 2 lần thật nhanh vào cùng một ô (cùng lệnh ở mục 4). Chỉ được có một lệnh: tab Nhật ký trên remote và nhật ký lệnh trên host chỉ có một dòng mới.
7. **Rời tab giữa chừng.** Đang lệnh Bật mà chuyển sang tab Giám sát rồi quay lại: ô vẫn đang quay. Khi kết quả đã hiện mà quay lại tab: không phát lại dấu tích.
8. ⚠️ **Tắt hiệu ứng.** `adb shell settings put global animator_duration_scale 0` (ghi lại để khôi phục về giá trị cũ, thường là 1). Mở lại tab: không còn gì quay mãi, trạng thái đổi ngay. Lặp lại bước 2.
9. **Trợ năng.** Trong `ui.xml`, mỗi ô nguồn có `content-desc` đúng nhãn và `clickable="true"`. Ô đang bận có nhãn kèm trạng thái và `enabled="false"`. Tiêu đề "NGUỒN" và "VÙNG NGUY HIỂM" có mặt.
10. **Thẻ cập nhật** (tùy chọn, dài). Cài lên remote một bản cũ hơn (versionCode < 27) trong khi host chứa bản 1.26 (27). Mong đợi: thẻ xanh "Có bản cập nhật v1.26 (… MB)" trượt vào. Bấm Cập nhật: thanh tiến trình chạy, có nút Hủy. Cuối cùng hiện màn cài đặt của Android (👤 người dùng cài). Thử Hủy giữa chừng: thẻ quay về trạng thái "Có bản cập nhật".

### 5.2 Thanh chọn PC (nhiều PC)

1. Khi host chỉ có một PC: không có thanh chip.
2. ⚠️ Thêm PC thứ hai trên host: Cài đặt PC → "+ Thêm PC", đặt tên "PC thử", IP là một địa chỉ LAN không dùng, MAC giả, Lưu. Remote: trong vài giây có hàng chip ở trên cùng; chip PC thật có chấm xanh tỏa vòng, "PC thử" có chấm đỏ. Thanh chip không hiện ở tab Nhật ký.
3. Bấm chip "PC thử": chip đổi màu mượt, thẻ trạng thái và tab Giám sát chuyển sang PC đó (PC tắt hoặc không phản hồi). Bấm lại PC thật để quay về.
4. Đóng hẳn app remote rồi mở lại: vẫn giữ PC đã chọn.
5. ⚠️ Xóa "PC thử" trên host ("Xóa PC này"): thanh chip biến mất, remote quay về PC còn lại.
6. `content-desc` của chip có dạng `<tên>, đang bật` hoặc `<tên>, tắt hoặc mất kết nối`.

### 5.3 Kết nối lại và làm mới

1. Đưa remote ra nền khoảng 5 phút rồi mở lại. Đo thời gian đến khi thẻ trạng thái có kết quả (quay video), kỳ vọng vài giây. Không phải tắt app mở lại.
2. 👤 Bật rồi tắt chế độ máy bay trên remote. Thẻ trạng thái hiện "Đang kết nối lại..." hoặc "Đang kết nối Tailscale...", rồi tự hồi phục; nút làm mới quay trong lúc chờ.

### 5.4 Máy ở nhà mất mạng (đọc danh sách thiết bị Tailscale)

👤 Tắt Wi-Fi và dữ liệu di động của **máy host** khoảng 2 phút. Remote: bấm làm mới. Mong đợi: nhanh chóng hiện "Máy ở nhà đang offline", kèm "· lần cuối online ..." nếu Tailscale biết; không phải chờ hết thời gian. Bật lại mạng host: trạng thái hồi phục. Ghi lại thời gian từng bước.

### 5.5 Ghép đôi bằng QR

1. Host: Thiết bị điều khiển từ xa → "Thêm thiết bị". Hiện mã QR, mã 6 ký tự và thời gian hết hạn. Chụp màn hình.
2. ⚠️ Ghép đôi thật cần remote chưa ghép đôi. Hoặc dùng một điện thoại thứ ba, hoặc 👤 "Quên host này" trên remote (sẽ phải ghép lại). Trên remote chưa ghép đôi: "📷 Quét mã QR trên máy ở nhà" mở camera, 👤 người dùng quét, ghép đôi xong không phải gõ mã. Thử thêm cách nhập tay 6 ký tự.
3. Nếu không ghép thật: bấm "Hủy mã ghép đôi" trên host, mã phải biến mất.

### 5.6 Khóa ứng dụng (cả host và remote)

Remote: Thêm → "🔒 Khóa ứng dụng". Host: nút "🔒 Khóa ứng dụng" trên màn chính.

1. ⚠️ Bật "Khóa bằng mã 6 số" với mã thử (mục Quy tắc), nhập lại để xác nhận.
2. "Khóa lại khi nào": thử từng lựa chọn, mỗi lần rời app bằng nút Home rồi quay lại:
   - Ngay khi rời app: khóa ngay;
   - 30 giây (mặc định): quay lại trước 30 s thì không khóa, sau 30 s thì khóa;
   - 1 phút và 5 phút: tương tự;
   - Chỉ khi tắt màn hình / khóa máy: rời app lâu bao nhiêu cũng không khóa; 👤 tắt màn hình rồi mở lại thì khóa.
3. Nhập sai mã: hiện "Sai mã. Số lần thử còn lại: ...". Sai nhiều lần thì bị chờ ("Sai mã quá nhiều lần...").
4. 👤 "Dùng vân tay / khuôn mặt": bật lên, mở khóa bằng sinh trắc học.
5. Remote: bật "Yêu cầu xác thực cho Tắt máy/Ngủ đông". ⚠️ Bấm Tắt máy phải hỏi xác thực trước. Hủy ở bước xác thực, đừng tắt máy thật.
6. Khi xong: tắt khóa ứng dụng (phải nhập mã hiện tại).

### 5.7 Cảnh báo máy ở nhà

1. Remote: Thêm → bật "Cảnh báo khi máy ở nhà mất kết nối hoặc yếu pin". Trên Android 13 trở lên phải hỏi quyền thông báo. Kiểm tra job đã đăng ký:
   ```powershell
   adb -s $r shell dumpsys jobscheduler | Select-String "vn.vuumi.pcdash.remote" -Context 0,3
   ```
2. Chạy job ngay thay vì chờ 15 phút (job id 4201):
   ```powershell
   adb -s $r shell cmd jobscheduler run -f vn.vuumi.pcdash.remote 4201
   ```
3. 👤 Tắt mạng máy host, chạy job 2 lần (cách nhau vài giây). Mong đợi thông báo "Máy ở nhà mất kết nối". Bật mạng lại, chạy job: thông báo "Máy ở nhà đã kết nối lại".
4. Pin yếu: nếu máy host dưới 20% và không sạc, chạy job thì có "Pin máy ở nhà yếu". Nếu không giả được thì SKIP.
5. Bật thêm "Báo cả khi PC tắt (trừ khi tắt / Sleep từ app này)". ⚠️ Tắt PC bằng cách khác (người dùng tự tắt trên PC), chạy job: có thông báo "... đã tắt hoặc mất kết nối". ⚠️ Cho PC Sleep từ app, chạy job: **không** có thông báo.
6. Có nhiều PC thì thông báo ghi tên PC.
7. Khi xong: tắt cảnh báo nếu người dùng không dùng.

### 5.8 Ô Cài đặt nhanh và lối tắt

1. 👤 Thêm ô "Bật PC" vào Cài đặt nhanh của remote. ⚠️ Bấm ô: app mở và chạy lệnh Bật (chỉ khi người dùng đồng ý).
2. Nhấn giữ icon PCDash Remote: có lối tắt "Bật PC" và "Cho PC ngủ" (chỉ kiểm tra là có, ⚠️ bấm thì hỏi trước).

### 5.9 Ngôn ngữ (Android 13 trở lên)

```powershell
adb -s $r shell cmd locale set-app-locales vn.vuumi.pcdash.remote --locales en-US
```

Mở lại app: mọi màn hình bằng tiếng Anh, kể cả tab Điều khiển Compose ("PC is ON", "POWER", "DANGER ZONE") và thanh chip. Chụp từng tab. Trả về tiếng Việt bằng `--locales vi-VN` (hoặc theo ngôn ngữ cũ đã ghi lại). Làm tương tự với host (`vn.vuumi.pcdash`).

### 5.10 Tràn viền và bàn phím (Android 15 trở lên)

Không có gì bị che dưới thanh trạng thái, thanh điều hướng hay góc bo. Ở màn ghép đôi, ô nhập vẫn nằm trên bàn phím. Hộp thoại có/không dễ đọc trên nền đen.

## 6. Kiểm tra trên host

1. Màn chính: các dòng Trạng thái, Dịch vụ host, Cổng :8091 và Tối ưu pin đúng thực tế.
2. Cài đặt PC: "DANH SÁCH PC" và "Quét mã QR từ PC". 👤 Quét QR trên PC thì điền sẵn IP, MAC, cổng, token; hiện "Đã quét xong. Kiểm tra rồi bấm Lưu cài đặt". Không lưu nếu người dùng không muốn.
3. Nút trên host có cảm giác bấm, hiện "Đang gửi..." hoặc "Đã gửi", và chặn bấm lặp ("Đang gửi lệnh trước, đợi một chút..."). ⚠️ Nút nguồn trên host cũng phải hỏi trước.
4. Dashboard web `http://<IP host>:8090` vẫn mở được và đổi được ngôn ngữ.

## 7. Báo cáo

Viết `remote\build\device-check\report.md` bằng tiếng Việt:

- Bảng: mục (ví dụ 5.1.4), PASS/FAIL/SKIP, bằng chứng (file), ghi chú.
- Lỗi xếp theo mức độ. Mỗi lỗi ghi: bước tái hiện, mong đợi, thực tế, bằng chứng, nghi vấn nguyên nhân (file:dòng nếu tìm được).
- Hiệu năng so với mốc cũ (PSS, jank, p99, ANR, crash), kích thước APK, thời gian build.
- Nhận xét giao diện theo checklist mục 4 của `pcdash-review`.
- Thiết bị đã dùng: model, phiên bản Android, serial rút gọn.

Đề nghị sửa nếu có lỗi. Chỉ sửa khi người dùng bảo; sau khi sửa phải chạy lại test (mục 2), cài lại (mục 3) và kiểm tra lại đúng mục lỗi.

## 8. Dọn dẹp (bắt buộc)

Khôi phục mọi thứ đã đổi và liệt kê trong báo cáo:

- `animator_duration_scale` (cũng như `transition_animation_scale` và `window_animation_scale` nếu đã đổi) về giá trị cũ;
- ngôn ngữ app của host và remote;
- chế độ máy bay, Wi-Fi của host;
- xóa "PC thử" khỏi Cài đặt PC;
- tắt khóa ứng dụng dùng mã thử, hủy mã ghép đôi còn treo;
- trạng thái cảnh báo như người dùng muốn;
- xóa file trên điện thoại: `adb shell rm /sdcard/fx.mp4 /sdcard/ui.xml`.
