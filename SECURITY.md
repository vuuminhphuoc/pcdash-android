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
| Host to PcAgent `:8799` | Same LAN as the PC | `X-PcAgent-Token` on `/exec`, `/metrics`, `/quota`; Windows firewall rule limited to the local subnet |
| Setup QR | Whoever sees the screen | Contains IP, MAC, port and agent token; shown only on demand, auto-closes, and the host accepts it only for private (RFC1918) addresses |
| Pairing | Same private LAN only | Private (RFC1918) addresses only; refused until the host is signed in to Tailscale |

## Things you must do

- Keep `:8090` and `:8799` off the Internet. Do not port-forward them.
- Never commit `agent.token`, `tailscale.txt`, keystores or any file holding an access code.
- Do not share a screenshot or QR code of the PcAgent setup window (`PcAgent.exe --setup`): it contains the agent token. The window closes itself after 10 minutes. The host asks for camera permission only to scan this code; it never records or stores images.
- Remove a lost phone with **Thiết bị điều khiển từ xa -> Xóa thiết bị** on the host, then delete its node in the Tailscale admin console.
- Rotate the agent token by deleting `%LOCALAPPDATA%\PcAgent\agent.token` and running the installer again, then enter the new token on the host.
- Release APKs are signed with a keystore generated locally by the build scripts (default password `android`). That is fine for personal builds; use your own protected keystore before distributing APKs to others.

## Known limits

- PcAgent speaks plain HTTP inside your LAN. Anyone who can sniff your LAN can read the token. Use a trusted Wi-Fi network.
- The embedded Tailscale node key expires according to your tailnet policy; the apps show "Cần đăng nhập" when that happens.
