# Third-party software

PCDash itself is MIT licensed (see `LICENSE`). It builds on:

| Component | Used for | License |
|---|---|---|
| [tailscale.com `tsnet`](https://github.com/tailscale/tailscale) and the Go modules it pulls in (29 modules in `libpcnet.so`, see `pcnet/go.mod`, grouped below) | Embedded userspace Tailscale node in both Android apps (`pcnet/`, built into `libpcnet.so`) | BSD-3-Clause, MIT and Apache-2.0 (each module keeps its own license) |
| [TailSocks](https://github.com/bropines/tailsocks) | `pcnet/netmon_android.go` is adapted from its Android network-interface getter. Full notice in `pcnet/THIRD_PARTY_NOTICES.md` | BSD-3-Clause |
| [wlynxg/anet](https://github.com/wlynxg/anet) | Network interface listing on Android | BSD-3-Clause |
| Go standard library | Runtime of `libpcnet.so` | BSD-3-Clause |
| [LibreHardwareMonitorLib](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor) 0.9.3 (optional) | CPU package temperature through `agent/CpuTempHelper.cs`. Downloaded by `agent/download_lhm.ps1` with a pinned SHA-256; not stored in this repository. The Windows release zip ships the unmodified DLL in `build\`; its source is at the link | MPL-2.0 |
| [ZXing core](https://github.com/zxing/zxing) 3.5.3 | Decodes the setup QR code in the host app (`QrDecoder`). Downloaded by `scripts/download-zxing.ps1` with a pinned SHA-256 and compiled into the host APK; not stored in this repository | Apache-2.0 |

## Go modules inside `libpcnet.so`

Checked against the LICENSE file of each module when this file was written. All are permissive; none is GPL or LGPL.

- **BSD-style:** tailscale.com, filippo.io/edwards25519, github.com/creachadair/msync, github.com/go-json-experiment/json, github.com/hdevalence/ed25519consensus, github.com/huin/goupnp, github.com/tailscale/peercred, github.com/wlynxg/anet, go4.org/netipx, golang.org/x/{crypto,exp,net,oauth2,sync,sys,term,text,time}
- **MIT:** github.com/fxamacker/cbor/v2, github.com/gaissmai/bart, github.com/mdlayher/socket, github.com/tailscale/wireguard-go, github.com/x448/float16
- **Apache-2.0:** github.com/golang/groupcache, github.com/google/btree, github.com/klauspost/compress, github.com/pires/go-proxyproto, go4.org/mem, gvisor.dev/gvisor

## If you distribute built files

This repository ships source only, so the MIT `LICENSE` plus this file are enough. A built APK also contains
the code above (and ZXing in the host app). When you hand out APKs, include `LICENSE`, this file and
`pcnet/THIRD_PARTY_NOTICES.md`, and keep the license text of the Apache-2.0 components available (ZXing and
the Apache-2.0 modules listed above). The MPL-2.0 LibreHardwareMonitor DLL is never part of an APK. The
Windows agent zip (`scripts/package-release.ps1`) includes it unmodified for the optional CPU temperature
helper; under MPL-2.0 its source stays available from the LibreHardwareMonitor repository linked above
(https://www.mozilla.org/MPL/2.0/ for the license text).

Tailscale is a trademark of Tailscale Inc. PCDash is an independent project and is not affiliated with
or endorsed by Tailscale.

Android platform tools (SDK, NDK, `aapt2`, `d8`, `apksigner`) are required to build but are not
redistributed here.
