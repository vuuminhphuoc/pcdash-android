# PCDash

[English](README.md) | **Tiếng Việt**

<p align="center"><img src="docs/logo/pcdash-host.svg" width="96" alt="PCDash"> <img src="docs/logo/pcdash-remote.svg" width="96" alt="PCDash Remote"></p>

Điều khiển PC ở nhà bằng điện thoại Android: bật (Wake-on-LAN), Sleep, Hibernate, Shutdown và hủy lệnh. Có dashboard `:8090` trong mạng nhà và APK `PCDash Remote` để điều khiển từ xa mà máy điều khiển không cần bật VPN/Tailscale app.

## Mục tiêu

- Tận dụng một điện thoại Android cũ để ở nhà, biến nó thành bộ điều khiển PC có thể truy cập từ xa.
- Không phụ thuộc cloud bên thứ ba: kết nối từ xa dùng tailnet Tailscale của bạn.
- Không cần mở cổng router, không cần dịch vụ chạy trên VPS.

## Kiến trúc

```
┌─────────────────────────┐       tsnet userspace, không VPN app
│ Máy điều khiển Android  │ ─────────────────────────────────────┐
│ PCDash Remote           │                                      │
└─────────────────────────┘                                      ▼
                                                     ┌────────────────────┐
                                                     │ Điện thoại ở nhà   │
                                                     │ PCDash host        │
                                                     │ :8091 Bearer API   │
                                                     │ :8090 dự phòng     │
                                                     └─────────┬──────────┘
                                                               │
                                          WOL magic packet     │ HTTP :8799
                                          trong LAN            ▼
                                                     ┌────────────────────┐
                                                     │ PC Windows         │
                                                     │ PcAgent.exe        │
                                                     └────────────────────┘

Ghép đôi lần đầu: hai điện thoại cùng Wi-Fi nhà, mã tạm thời, SRP-6a.
```

- **Host** là app `app/` trên điện thoại ở nhà. Giữ dashboard `:8090` trong mạng nhà làm đường dự phòng; API mới `:8091` yêu cầu `Authorization: Bearer <token>` để điều khiển từ APK Remote. Host phát WOL trong LAN và gọi PcAgent trên PC.
- **PCDash Remote** chạy trên máy Android điều khiển, dùng tsnet Go userspace trong app để vào tailnet, không cần bật Tailscale app/VPN. Kết nối chỉ hoạt động khi mở app; khi đóng app, tsnet dừng (nếu bật Cảnh báo máy ở nhà, app mở tsnet vài giây mỗi lần kiểm tra nền rồi dừng lại). Không có relay/cloud riêng.
- **Ghép đôi** chỉ thực hiện trong cùng Wi-Fi/LAN nhà. Sau đó Remote gọi API host qua tailnet; Wake do host ở trong LAN thực hiện.
- **PC** chạy `PcAgent.exe` lắng nghe `:8799`, thực thi Sleep/Hibernate/Shutdown/Abort.

## Thành phần

| Thành phần | Mô tả |
|---|---|
| `app/` | App Android host (Java), dashboard `:8090`, API Bearer `:8091`, giao diện cấu hình Tailscale và quản lý thiết bị. Build bằng `build.ps1`. |
| `app/src/.../WebDashboard.java` | Dashboard HTTP `:8090` và API hiện có; giữ nguyên làm đường dự phòng. |
| `app/src/.../RemoteApiServer.java` | API HTTP `:8091`, xác thực Bearer và chuyển tiếp trạng thái/lệnh qua `PcConn`. |
| `app/src/.../RemoteDeviceStore.java` | Lưu thông tin thiết bị đã ghép và hash token, không lưu token thô. |
| `remote/` | APK `PCDash Remote` cho máy điều khiển; pairing, giao diện điều khiển và cầu JNI tới tsnet. Build bằng `build-remote.ps1`. |
| `pcnet/` | Thư viện Go tsnet userspace, đóng gói native cho `arm64-v8a` và `armeabi-v7a`. |
| `shared/` | Giao thức ghép đôi SRP-6a dùng chung giữa host và Remote. |
| `app/src/.../PcConn.java` | Gửi WOL, kiểm tra trạng thái PC (TCP probe), gọi agent `/exec`. |
| `agent/PcAgent.cs` | Agent Windows lắng nghe `:8799`, xử lý `sleep`, `hibernate`, `shutdown`, `abort`, `ping`. |
| `agent/build_agent.ps1` | Biên dịch agent bằng `csc.exe`, không cần Python/.NET SDK. |
| `agent/install.bat` | Cài agent, sinh token truy cập, mở firewall TCP 8799 (chỉ mạng nội bộ) và tạo shortcut Startup. |
| `build.ps1`, `build-remote.ps1`, `build-all.ps1` | Build APK host và remote; `build-all.ps1` tự động đóng gói APK remote vào assets của host để phục vụ tự cập nhật. |
| `scripts/test-*.ps1` | Runner kiểm thử toolchain, host API, pairing, UI, remote Java và pairing E2E. |
| `scripts/install-go-toolchain.ps1` | Cài Go riêng trong `tools/`, không thay Go hệ thống. |

## Cài đặt

### 1. Tài khoản Tailscale

Chỉ cần một tài khoản Tailscale (gói miễn phí đủ dùng). Không cần OAuth client, tag hay ACL tùy chỉnh: ACL mặc định của tailnet cá nhân cho phép các thiết bị của bạn truy cập nhau. Điện thoại host và điện thoại remote đăng nhập **cùng một tài khoản** trong chính app PCDash (không cần app Tailscale).

### 2. Tải bản dựng sẵn hoặc build APK

Có thể tải `pcdash-host-*.apk`, `pcdash-remote-*.apk` và `pcdash-agent-windows-*.zip` ở trang [Releases](https://github.com/vuuminhphuoc/pcdash-android/releases) rồi bỏ qua phần build bên dưới.

Cần Android SDK, JDK 17 (ví dụ Amazon Corretto 17), Android NDK `27.1.12297006`, PowerShell và Go `1.27.1` trở lên. SDK được tìm ở `%LOCALAPPDATA%\Android\Sdk` (hoặc đặt `ANDROID_HOME`); script tự tìm JDK 17 trong `~\.jdks` hoặc Program Files, nếu không thấy thì đặt `PCDASH_JAVA_HOME` (hoặc `JAVA_HOME`) trỏ tới JDK 17. Cài Go vào thư mục dự án `tools/`:

```powershell
$env:JAVA_HOME = "C:\Path\To\corretto-17"
.\scripts\install-go-toolchain.ps1 -Version 1.27.1
.\build-all.ps1
# Hoặc build riêng từng app: .\build-remote.ps1; .\build.ps1
```

Hai APK được tạo tại `app\build\pcdash-signed.apk` và `remote\build\pcdash-remote-signed.apk`.

### 3. Cài APK

Ghép nối thiết bị Android với adb (có thể dùng Gỡ lỗi không dây trên Android 11 trở lên), rồi cài từng APK:

```powershell
adb install -r app\build\pcdash-signed.apk
adb shell am start -n vn.vuumi.pcdash/.MainActivity
adb install -r remote\build\pcdash-remote-signed.apk
adb shell am start -n vn.vuumi.pcdash.remote/.RemoteMainActivity
```

Trên điện thoại Xiaomi/MIUI, bật **Cài đặt qua USB** nếu MIUI chặn cài APK bằng adb.

### Quy trình phát hành bản mới (Tự cập nhật Remote)

1. **Lần đầu tiên**: phải cài PCDash Remote bằng adb (để app có sẵn tính năng tự cập nhật).
2. **Khi phát hành bản mới**:
   - Tăng `versionCode` và `versionName` trong `remote/version.properties` (và `app/version.properties` nếu có sửa host).
   - Chạy `.\build-all.ps1` để build remote, nhúng APK mới vào `app/assets/remote-update.apk` (kèm SHA-256 và size trong `remote-update.json`), rồi build host.
   - Cài lại host trên điện thoại ở nhà: `adb install -r app\build\pcdash-signed.apk`.
   - Trên máy điều khiển: mở PCDash Remote, app tự phát hiện bản cập nhật mới từ host; bấm nút **Cập nhật** để tải và cài đặt một chạm qua tsnet mà không cần adb hay cáp nối.

### CI và phát hành trên GitHub

`.github/workflows/build.yml` build hai APK và PcAgent trên Windows, chạy mọi script kiểm thử và test Go của `pcnet` ở mỗi lần push, rồi đính kèm các tệp phát hành vào lượt chạy. Push tag `v*` tạo thêm bản phát hành nháp (draft) với `pcdash-host-<tag>.apk`, `pcdash-remote-<tag>.apk`, `pcdash-agent-windows-<tag>.zip` và `SHA256SUMS.txt` (`scripts\package-release.ps1` tạo đúng các tệp này trên máy). APK phát hành phải ký bằng khóa của bạn, nên bước phát hành dừng lại nếu repo chưa có các secret sau:

| Secret | Giá trị |
|---|---|
| `PCDASH_HOST_KEYSTORE_B64` | `app\pcdash.keystore` dạng base64: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("app\pcdash.keystore"))` |
| `PCDASH_REMOTE_KEYSTORE_B64` | `remote\pcdash-remote.keystore` dạng base64 |
| `PCDASH_KEYSTORE_PASSWORD` | Mật khẩu keystore, chỉ cần nếu khác mặc định `android` |

### 4. Cài PcAgent trên PC Windows

Mở `agent\install.bat` (hoặc `agent\install.ps1`) và bấm **Yes** ở hộp UAC. Script chép agent vào `%LOCALAPPDATA%\PcAgent`, thêm luật firewall cho cổng 8799 (chỉ mạng nội bộ), tạo shortcut Startup và chạy agent bằng quyền người dùng thường, không có cửa sổ. Script cũng **sinh token truy cập** lưu ở `%LOCALAPPDATA%\PcAgent\agent.token` (chỉ người dùng hiện tại đọc được), in ra màn hình, chép vào clipboard và **mở cửa sổ mã QR cài đặt** (chứa IP, MAC, cổng, token). Mở lại mã QR bất cứ lúc nào từ Start menu ("PCDash - Ma QR cai dat") hoặc chạy `agent\show-qr.bat`; cửa sổ tự đóng sau 10 phút. Agent từ chối mọi yêu cầu `/exec`, `/metrics`, `/quota` không kèm header `X-PcAgent-Token` đúng. Cài lại giữ nguyên token; muốn đổi thì xóa `agent.token` rồi cài lại. Gỡ bằng `agent\uninstall.ps1`. Không còn bước `netsh http add urlacl`. Nếu cần biên dịch lại agent, chạy `agent\build_agent.ps1`.

**Nhiệt độ CPU (tùy chọn).** Không có quyền admin, PcAgent chỉ đọc được nhiệt độ hệ thống ACPI (`sys.tempC`, hiện "Hệ thống (ACPI)"). Để có nhiệt độ gói CPU thật: chạy `agent\build_agent.ps1 -WithHelper` (tải `LibreHardwareMonitorLib` 0.9.3 từ NuGet, kiểm hash SHA-256 đã ghim), rồi mở PowerShell **Run as Administrator** chạy `agent\install-cputemp-task.ps1`. Script cài `CpuTempHelper.exe` vào `%ProgramFiles%\PcAgentCpuTemp` (chỉ admin ghi được), tạo tác vụ chạy lúc đăng nhập với quyền cao nhất và ghi kết quả vào `%ProgramData%\PcAgentCpuTemp\cputemp.txt` (người dùng thường chỉ đọc được). Gỡ bằng `-Uninstall`. Driver kernel của LibreHardwareMonitor có thể bị phần mềm diệt virus cảnh báo.

### 5. Cấu hình PC trên điện thoại host

Mở app host, bấm **Cài đặt PC** rồi **Quét mã QR từ PC** và hướng camera vào mã QR trên màn hình PC (cho phép quyền camera lần đầu). Một lần quét điền sẵn IP LAN, địa chỉ MAC, cổng và token; kiểm tra rồi bấm **Lưu cài đặt**. Có thể nhập tay thay cho việc quét: IP LAN của PC (nên đặt DHCP reservation ở router), địa chỉ MAC (cho Wake-on-LAN), cổng agent (mặc định 8799) và token từ bước 4. Màn này cũng hiện **mã truy cập web** cho dashboard `:8090` (có nút sao chép và tạo mã mới). Không có giá trị nào được nhúng sẵn trong APK.

### 6. Đăng nhập và ghép đôi

1. **Host:** cài APK host, mở app, bấm **Đăng nhập Tailscale** (mở trình duyệt, xác nhận kết nối thiết bị). Thẻ Tailscale chuyển thành "Đang chạy (100.x.x.x)". Host nhúng tsnet riêng nên không cần app Tailscale.
2. **Remote:** cài APK remote, mở app, bước 1 bấm **Đăng nhập Tailscale** (cùng tài khoản), đợi "✓ Đã đăng nhập".
3. Đặt hai điện thoại trong cùng Wi-Fi nhà. Trên host mở **Thiết bị điều khiển từ xa → Thêm thiết bị**: màn hình hiện mã QR và mã 6 ký tự (5 phút). Trên remote bấm **📷 Quét mã QR trên máy ở nhà** (cần quyền camera) là ghép đôi luôn; hoặc nhập mã (host thường được tìm tự động, hoặc nhập IP LAN hiện dưới mã) rồi **Bắt đầu ghép đôi**. Mã dùng một lần, tối đa 5 lần nhập sai; chỉ IP LAN riêng được ghép.

Sau đó điều khiển được từ bất kỳ đâu, kể cả 5G, không cần mở VPN. Trên host, xóa thiết bị sẽ thu hồi token ngay (node còn trong trang Machines của Tailscale đến khi bạn xóa tay). **Quên host** trên Remote xóa cấu hình, trạng thái đăng nhập và cần xác thực màn hình khóa. Khóa node Tailscale hết hạn thì app báo "Cần đăng nhập" kèm nút đăng nhập lại; có thể tắt hết hạn khóa trong trang Machines.

## Sử dụng

Dashboard dự phòng mở tại `http://<IP-điện-thoại>:8090` trong LAN (hoặc `http://<tailscale-ip>:8090` nếu điện thoại host có cài app Tailscale). Dashboard từ chối mọi địa chỉ IP công cộng; lần đầu bấm lệnh trang hỏi **mã truy cập web** (xem trong Cài đặt PC trên host). PCDash Remote kết nối host qua tailnet mà không cần bật VPN/Tailscale app trên máy điều khiển.

- **Bật / Đánh thức**: host gửi magic packet WOL trong LAN.
- **Sleep**: `rundll32 powrprof.dll,SetSuspendState`.
- **Hibernate**: `shutdown /h`.
- **Shutdown**: `shutdown /s /t 0`.
- **Hủy lệnh tắt**: `shutdown /a`.

Tính năng trong PCDash Remote:

- **Nhật ký lệnh**: host ghi mỗi lệnh (thiết bị, giờ, kết quả) vào `command_log.jsonl`, tối đa 500 dòng. Xem trên remote bằng nút **Nhật ký** hoặc trong màn **Thiết bị điều khiển từ xa** của host.
- **Biểu đồ**: host lấy mẫu mỗi 30 giây và giữ 6 giờ CPU/GPU, RAM, mạng, nhiệt độ (`/v1/history`); remote vẽ 1 giờ hoặc 6 giờ.
- **Xác thực**: Hibernate, Shutdown và **Quên host** đòi vân tay/khuôn mặt/PIN màn hình khóa (hộp thoại xác nhận nếu máy không có khóa). Công tắc cho Hibernate/Shutdown nằm trong cài đặt remote; Quên host luôn cần xác thực.
- **Kiểm tra cập nhật**: nút **Kiểm tra cập nhật** ở cuối màn hình. APK cập nhật tải vào cache riêng của app và bị xóa mỗi lần mở lại app.
- **Điều hướng**: thanh tab dưới cùng gồm Điều khiển, Giám sát, Quota, Nhật ký và Thêm (Cài đặt, Quên host). App luôn mở ở Điều khiển.
- **Quota**: PcAgent chạy `omp usage --json --redact` (đường dẫn tùy chỉnh bằng `PCAGENT_OMP_PATH`), gộp theo nhà cung cấp và nhóm, phục vụ `GET /quota`; host chuyển tiếp ở `/v1/quota` và giữ số cũ khi PC tắt. Mỗi thẻ hiện thanh gộp 5 giờ và tuần, chạm để xem mọi cửa sổ và từng tài khoản. Nhãn tài khoản là email đã che của omp (`ten@*`); không gửi email đầy đủ, accountId hay token. Claude chưa có trong tab này.
- **Lối tắt**: ô **Bật PC** trong Cài đặt nhanh và lối tắt **Bật PC** / **Cho PC ngủ** khi nhấn giữ biểu tượng PCDash Remote. Chúng chỉ mở app với lệnh chờ sẵn; app gửi lệnh khi đã kết nối (tối đa 60 giây sau khi bấm), không có gì chạy nền.
- **Cảnh báo máy ở nhà** (tùy chọn, tắt sẵn, trong **Thêm**): remote đọc danh sách thiết bị Tailscale để biết ngay điện thoại ở nhà còn online không (không phải chờ request hết thời gian). Thông báo khi máy ở nhà offline trên Tailscale, khi máy vẫn online nhưng app PCDash host không trả lời (2 lần kiểm tra liên tiếp), khi kết nối lại, và khi pin ≤ 20% mà không sạc. Tùy chọn thêm: báo khi PC tắt / bật lại (trạng thái PC do máy ở nhà báo; lệnh Sleep / Tắt máy gửi từ remote không gây cảnh báo). Khi máy ở nhà không trả lời, tab Điều khiển cũng ghi rõ Tailscale báo offline (kèm lần cuối online) hay vẫn online mà app host không phản hồi.
- **Khóa ứng dụng** (tùy chọn, cả host và remote): mã 6 số, có thể mở bằng vân tay / khuôn mặt (Android 10 trở lên). App khóa khi mở, sau đó khóa lại theo lựa chọn: ngay khi rời app, rời app quá 30 giây / 1 phút / 5 phút, hoặc chỉ khi tắt màn hình / khóa máy; nhập sai 5 lần phải chờ 30 giây, tăng gấp đôi mỗi lần sai tiếp (tối đa 16 phút). Mã không được lưu, chỉ lưu HMAC với khóa trong Android Keystore. Quên mã thì xóa dữ liệu ứng dụng.
- **Ngôn ngữ**: hai app, dashboard web và cửa sổ QR trên PC có tiếng Anh và tiếng Việt, tự theo ngôn ngữ máy. Android 13 trở lên chọn riêng trong Cài đặt > Ứng dụng > PCDash > Ngôn ngữ. Chuỗi giao diện nằm trong `L10n.t("English", "Tiếng Việt")` (`shared/src/vn/vuumi/pcdash/i18n/L10n.java`); `scripts\lint-l10n.ps1` báo lỗi nếu có chữ tiếng Việt viết cứng ngoài `L10n.t`.
- **Host bền vững**: wake lock và Wi-Fi lock, tự chạy lại sau reboot hoặc khi cài bản mới, watchdog mỗi 15 phút, thẻ **Sức khỏe host** (loại trừ tối ưu pin, tự khởi động Xiaomi). Nên bật Always-on VPN cho Tailscale trong cài đặt hệ thống.

## Bảo mật

- Mọi đường điều khiển đều cần xác thực: `:8091` (Remote) dùng Bearer token theo từng thiết bị và chỉ nghe loopback, truy cập từ ngoài đi qua node tsnet của host; `:8090` (dashboard web) cần header `X-PCDash-Token` (mã truy cập web), header tùy chỉnh này cũng chặn yêu cầu chéo trang từ trình duyệt; `:8799` (PcAgent) cần header `X-PcAgent-Token`. Không expose `:8090` hoặc `:8799` ra Internet. Xem thêm [SECURITY.md](SECURITY.md).
- Host chỉ lưu hash token thiết bị; không lưu khóa API hay secret Tailscale nào (đăng nhập tương tác, trạng thái nằm trong thư mục riêng của app). `allowBackup=false` trên host và remote.
- Ghép đôi dùng SRP-6a, mã chỉ mở sau khi bấm **Thêm thiết bị**, hết hạn sau 5 phút và bị hủy sau 5 lần nhập sai. Pairing chỉ nhận IP LAN riêng.
- Xóa thiết bị trên host thu hồi token ngay và yêu cầu xóa node khỏi tailnet.
- PcAgent dùng HTTP trong LAN; không mở cổng `:8799` ra Internet.

## Hạn chế

- Host yêu cầu Android minSdk 26; PCDash Remote yêu cầu minSdk 24.
- Ghép đôi chỉ hoạt động khi hai thiết bị ở cùng Wi-Fi/LAN nhà.
- Remote chỉ kết nối khi app đang mở, không chạy nền, trừ khi bật **Cảnh báo máy ở nhà** (kiểm tra khoảng 15 phút một lần; Android có thể hoãn khi máy để yên lâu; sau khi khởi động lại máy remote cần mở app một lần để lịch kiểm tra chạy lại).
- Wake cần điện thoại host đang chạy trong LAN và PC hỗ trợ Wake-on-LAN (bật WOL trong BIOS và driver NIC).
- Nhiệt độ CPU thật cần quyền admin một lần (xem phần PcAgent).
- IP PC nên đặt DHCP reservation ở router để địa chỉ trong Cài đặt PC không đổi.
- PcAgent dùng HTTP thường trong LAN; chỉ dùng trên mạng Wi-Fi bạn tin cậy.

## Kiểm thử

Các runner PowerShell hiện có:

```powershell
.\scripts\test-build-toolchain.ps1
.\scripts\test-host-api.ps1
.\scripts\test-host-pairing.ps1
.\scripts\test-host-ui-model.ps1
.\scripts\test-remote-java.ps1
.\scripts\test-host-update.ps1
.\scripts\test-pairing-e2e.ps1
.\scripts\test-host-metrics.ps1
.\scripts\test-host-log-history.ps1
.\scripts\test-agent-metrics.ps1
.\scripts\lint-ui-thread.ps1
.\scripts\lint-l10n.ps1
```

## Giấy phép

[MIT](LICENSE). Thư viện bên thứ ba xem [THIRD_PARTY.md](THIRD_PARTY.md).

## Logo

Biểu tượng nút nguồn: host màu xanh lá, remote màu xanh dương kèm sóng tín hiệu. Icon adaptive (`app/res`, `remote/res`) có lớp `monochrome` cho themed icon Android 13+; nền tối cố định nên hiển thị tốt trên hình nền sáng và tối. SVG trong `docs/logo/` tự đổi màu theo chế độ sáng/tối.

## Chống treo app (ANR)

Không gọi mạng, JNI, I/O đĩa hay khóa dùng chung trên luồng giao diện. `RemoteController.isPaired()` và `getBoundLocalPort()` không khóa, lệnh mạng chạy ngoài khóa, mở/đóng tsnet chạy trong `lifecycleExecutor`. StrictMode ghi log vi phạm. Kiểm tra bằng `scripts\lint-ui-thread.ps1` (quét token cấm, ngoại lệ ghi `// ui-thread-ok: lý do`), `UiThreadSafetyTest` trong `test-remote-java.ps1`, và `scripts\perf-smoke-remote.ps1` chạy trên điện thoại thật qua adb (giật khung, ANR, crash, rò luồng và RAM).
