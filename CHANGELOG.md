# Changelog

What changed in each release. Host and remote version numbers are in the release notes; the apps update
themselves from the host phone.

## 1.2.2 (host 1.27, remote 1.27)

- **Host main screen, easier to read**: the four settings buttons (PC settings, App lock, Language, Check for updates) sit in a 2 x 2 grid instead of four full-width rows, so the status cards come up the screen. The result of the last update check stays on screen with its time (it was a toast that vanished), the version shows under the title (the second, duplicate "PCDash" heading is gone), the update button reads "Check updates" / "Tìm bản mới" so it fits on one line, the health lines start with a check mark or a warning sign instead of relying on colour alone, and the footer note is easier to read.
- **Remote bottom bar and More sheet**: the five tabs use one set of vector icons that take the selection colour (the chart and clipboard emoji kept their own colours), and the Language and App lock buttons lose their emoji.
- **Autostart button only on Xiaomi, Redmi and POCO phones**, with a line that says Android cannot tell whether it is on.
- **Less leftover**: `pcagent.log` is cut to its newest part once it passes about 1 MB (it grew for ever), the agent removes a half-finished update folder at start, and the host clears the update APK it downloaded as soon as the app starts again.

## 1.2.1 (host 1.26, remote 1.26)

- **Check for updates in the host app**: a button on the main screen reads the latest GitHub release, and if it holds a newer host it downloads the APK, checks it against `SHA256SUMS.txt` and opens Android's install screen for you to confirm. Releases now carry a `pcdash-version.json` that says which host and remote versions they hold.
- Tests: the agent self-update test no longer takes minutes (it waited on a background job).

## 1.2.0 (host 1.25, remote 1.26)

- **Language button in both apps** (host main screen, remote under More): phone's language, English or Vietnamese. On Android 13+ it is the same setting as Settings > Apps > Language.
- Quota names from the PC ("5 giờ", "Tuần", "Chat Requests", "(shared)"...) follow the app language.
- **Restart, Lock and Sign out** for the PC, in the remote's Control tab, the host app and the web
  dashboard. Restart and Sign out ask for confirmation (and the phone's screen lock if "Require unlock"
  is on); Lock does not. Lock and Sign out act on the Windows session PcAgent runs in.
- **PcAgent updates itself**: once a day it reads the latest GitHub release of this project, checks the agent zip against `SHA256SUMS.txt` and swaps in the new `PcAgent.exe`. Turn it off with a file named `no-auto-update` next to `PcAgent.exe`. Agents installed before this change need one manual reinstall (`install.bat` from the new zip) to get it, and the new commands.
- **PcAgent requests are signed** (HMAC, timestamp and nonce) instead of carrying the token, so sniffing the LAN no longer reveals the token and a captured request cannot be replayed. A host from before this change still works (plain token); an empty file named `strict` next to `PcAgent.exe` refuses that. If the PC's and the phone's clocks differ by more than 5 minutes the host says so.
- **PcAgent audit**: failed and first-seen clients are logged to `pcagent.log`; five failures from one LAN address lock it out for a minute.
- Tests: the quota/agent fake-server tests no longer fail now and then on a busy machine (this broke the 1.1.0 CI run).

## 1.1.1 (host 1.24, remote 1.25)

- Fixed `install-cputemp-task.ps1` in the agent zip: in Windows PowerShell 5.1 (what "Run as
  administrator" opens) it stopped on a first install, and the scheduled task pointed at `C:\Program`
  instead of the helper. The apps are the same as 1.1.0.

## 1.1.0 (host 1.24, remote 1.25)

- **Several PCs**: the host keeps a list of PCs; the remote shows a chip bar to switch between them, with
  alerts per PC.
- **Pair by QR**: the host shows a QR code for pairing; the remote scans it instead of typing the
  6-character code.
- **App lock** on both apps: 6-digit passcode, optional fingerprint / face, choice of when to lock again.
- **Alerts** (optional): the remote warns when the home phone goes offline or is low on battery, and
  optionally when a PC turns off.
- **Control tab rebuilt in Jetpack Compose**: animated power tiles that show sending / waiting / done on
  the tile itself, touch glow, live status dot, refresh button, protection against double taps.
- **Faster reconnect** after the app was in the background, and a self-healing status. Opening the app
  shows "Connecting..." instead of a brief red error while the tunnel warms up.
- **English UI** in both apps, the web dashboard and the PC's QR window (follows the system language).
- **Quick Settings tile** "Wake PC" and app shortcuts for Wake / Sleep on the remote.
- Both apps target Android 16; input fields stay above the keyboard on Android 15+; readable yes/no
  dialogs on the black theme.
- The build moved to Gradle (Kotlin and Compose in the remote); CI builds, tests and packages every push.

## 1.0.1 (host 1.22, remote 1.23)

- The host web dashboard (`:8090`) drops connections from public IP addresses. Only this phone, the home
  LAN, link-local, IPv6 unique-local and Tailscale addresses are served, so it stays closed when mobile
  data gives the phone a routable address.
- The dashboard caps request headers at 8 KB and answers `431` instead of buffering an unbounded header.
- The dashboard reports a failed PC action as failed (`"ok": false`) instead of always reporting success.
- `uninstall.ps1` also removes the Start menu shortcut for the setup QR code.
