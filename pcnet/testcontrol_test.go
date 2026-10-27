package main

import (
	"context"
	"net/http/httptest"
	"testing"
	"time"

	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/logger"
)

func newTestControlServer(t *testing.T) (*testcontrol.Server, *httptest.Server) {
	t.Helper()
	netns.SetEnabled(false)
	t.Cleanup(func() {
		netns.SetEnabled(true)
	})

	derpMap := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	tc := &testcontrol.Server{
		DERPMap: derpMap,
		DNSConfig: &tailcfg.DNSConfig{
			Proxied: true,
		},
		MagicDNSDomain:   "tail-scale.ts.net",
		AllNodesSameUser: true,
	}
	ts := httptest.NewUnstartedServer(tc)
	ts.Start()
	tc.HTTPTestServer = ts
	t.Cleanup(func() {
		ts.Close()
	})
	return tc, ts
}

func waitForHomeDERPConnected(t testing.TB, ctx context.Context, s *tsnet.Server) {
	t.Helper()
	h := s.Sys().HealthTracker.Get()
	ms := s.Sys().MagicSock.Get()
	for {
		if r := ms.GetLastNetcheckReport(ctx); r != nil &&
			r.PreferredDERP != 0 &&
			!h.GetDERPRegionReceivedTime(r.PreferredDERP).IsZero() {
			return
		}
		select {
		case <-ctx.Done():
			t.Fatalf("waitForHomeDERPConnected: %v", ctx.Err())
		case <-time.After(20 * time.Millisecond):
		}
	}
}
