# Security

PCDash can sleep, hibernate and shut down a PC, so treat every credential as sensitive.

## Reporting a vulnerability

Please do not open a public issue for security problems. Use GitHub's private vulnerability
reporting on this repository (Security tab, "Report a vulnerability"). Include the affected
component (host app, remote app, PcAgent), the steps to reproduce, and the impact.

## Trust model

| Path | Who can reach it | Protection |
|---|---|---|
| Remote app to host `:8091` | Your tailnet only (embedded tsnet), plus loopback | Per-device Bearer token (only its hash is stored), device pairing with SRP-6a, a one-time code valid for 5 minutes and cancelled after 5 wrong tries |
| Web dashboard host `:8090` | Same LAN as the host phone (connections from public IP addresses are dropped) | `X-PCDash-Token` access code on every control request, custom header blocks cross-site requests |
| Host to PcAgent `:8799` | Same LAN as the PC | Every request to `/exec`, `/metrics`, `/quota` is signed: HMAC-SHA256 keyed with the agent token over timestamp, nonce, method, target and body, so the token never crosses the LAN and a captured request cannot be replayed (each nonce once, within 5 minutes). Five failed tries from one LAN address lock it out for a minute (the PC itself is exempt); failures and first-seen clients go to `pcagent.log`. Windows firewall rule limited to the local subnet. Traffic is still plain HTTP, so metrics can be read by someone sniffing the LAN |
| PcAgent self-update | GitHub over HTTPS | Once a day the agent reads the latest release of this repository, downloads the agent zip and `SHA256SUMS.txt`, refuses a zip whose SHA-256 differs, and replaces only `PcAgent.exe`. A compromised release or GitHub account would reach every agent, the same trust as installing the release by hand. Off for source builds; off for an install with a `no-auto-update` file next to `PcAgent.exe` or `PCAGENT_NO_UPDATE=1` |
| Setup QR | Whoever sees the screen | Contains IP, MAC, port and agent token; shown only on demand, auto-closes, and the host accepts it only for private (RFC1918) addresses |
| Pairing | Same private LAN only | Private (RFC1918) addresses only; refused until the host is signed in to Tailscale |
| App lock (optional, both apps) | Whoever holds the unlocked phone | 6-digit passcode stored only as HMAC(salt, code) with an Android Keystore key; 5 wrong codes start a 30 s wait that doubles up to 16 min and survives restarts; optional fingerprint / face |
| Remote shortcuts and Quick Settings tile | The phone's own launcher and System UI | Wake and Sleep only, through a non-exported activity; the request lives in memory for 60 s, so other apps cannot trigger a command through the exported main activity |

## Things you must do

- Keep `:8090` and `:8799` off the Internet. Do not port-forward them.
- An older host (before this change) still sends the plain token header, and PcAgent accepts it so the update order does not matter. That is not protected against sniffing: update the host, then create an empty file named `strict` next to `PcAgent.exe` (`%LOCALAPPDATA%\PcAgent`) and the agent refuses the plain token.
- Never commit `agent.token`, `tailscale.txt`, keystores or any file holding an access code.
- Do not share a screenshot or QR code of the PcAgent setup window (`PcAgent.exe --setup`): it contains the agent token. The window closes itself after 10 minutes. The host asks for camera permission only to scan this code; it never records or stores images.
- Remove a lost phone with **Thiết bị điều khiển từ xa -> Xóa thiết bị** on the host, then delete its node in the Tailscale admin console.
- Rotate the agent token by deleting `%LOCALAPPDATA%\PcAgent\agent.token` and running the installer again, then enter the new token on the host.
- Release APKs are signed with a keystore generated locally by the build scripts (default password `android`). That is fine for personal builds; use your own protected keystore before distributing APKs to others.

## Known limits

- PcAgent speaks plain HTTP inside your LAN. Anyone who can sniff your LAN can read the token. Use a trusted Wi-Fi network.
- The embedded Tailscale node key expires according to your tailnet policy; the apps show "Cần đăng nhập" when that happens.
