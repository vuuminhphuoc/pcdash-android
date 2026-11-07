PCDash Remote for Windows
=========================

Controls the PC at home from this Windows PC, the same as the Android PCDash Remote: power commands
(Wake, Sleep, Hibernate, Shutdown, Restart, Lock, Sign out), the monitor, the AI quota and the command log.
It connects over Tailscale with its own built-in node: you do not need the Tailscale app, a VPN or router
port forwarding.

First start
-----------
1. Run PCDashRemote.exe. Windows may show a SmartScreen warning because the file is not code-signed; the
   source is in the "winremote" folder of the project.
2. An icon appears in the notification area (near the clock) and the page opens in your browser at
   http://127.0.0.1:18092/. Left click the icon to open it again; right click for "Start with Windows" and
   "Quit".
3. Step 1 on the page: open the sign-in link and sign in to Tailscale with the same account as the home phone.
4. Step 2: on the home phone open PCDash host, tap "Add device", and type the phone's Wi-Fi address and the
   6-character code. This PC and the home phone must be on the same Wi-Fi for this step only. You pair once.

After that the page controls the home PC from any network.

Where things are kept
---------------------
%APPDATA%\PCDashRemote\config.json   the pairing (a device token, readable by your Windows user only)
%APPDATA%\PCDashRemote\tailnet\      the Tailscale node's keys
"Unpair" and "Sign out of Tailscale" in the page's menu forget them. To remove the pairing from the home
phone as well, delete the device in PCDash host.

Security
--------
The page listens on 127.0.0.1 only and checks the Host header and a per-start secret on every request, so
other websites open in your browser cannot drive it. The device token never reaches the page.

---

PCDash Remote cho Windows
-------------------------
Điều khiển PC ở nhà từ máy Windows này, giống app PCDash Remote trên Android: bật/tắt/ngủ/khóa/khởi động lại,
giám sát, quota AI và nhật ký lệnh. Kết nối qua Tailscale bằng node nhúng sẵn: không cần app Tailscale,
VPN hay mở cổng router.

Lần đầu: chạy PCDashRemote.exe, biểu tượng hiện ở khay hệ thống và trang mở trên trình duyệt
(http://127.0.0.1:18092/). Bước 1: đăng nhập Tailscale cùng tài khoản với máy ở nhà. Bước 2: trên điện thoại ở
nhà mở PCDash host, bấm "Thêm thiết bị", nhập địa chỉ Wi-Fi của điện thoại và mã 6 ký tự (chỉ bước này cần
cùng Wi-Fi). Chỉ ghép đôi một lần.
