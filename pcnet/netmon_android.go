// Copyright (c) 2026, Bropines (TailSocks Project)
// SPDX-License-Identifier: BSD-3-Clause

//go:build android

package main

import (
	"github.com/wlynxg/anet"
	"tailscale.com/net/netmon"
)

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifs, err := anet.Interfaces()
		if err != nil {
			return []netmon.Interface{}, nil
		}

		ret := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			addrs, err := anet.InterfaceAddrsByInterface(&ifs[i])
			if err != nil {
				ret[i] = netmon.Interface{Interface: &ifs[i]}
				continue
			}
			ret[i] = netmon.Interface{
				Interface: &ifs[i],
				AltAddrs:  addrs,
			}
		}

		return ret, nil
	})
}
