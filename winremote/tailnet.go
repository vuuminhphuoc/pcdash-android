package main

// The embedded Tailscale node: a tsnet.Server that signs in through the browser (no Tailscale app, no
// auth key) and dials the host phone over the tailnet. Same idea as pcnet/ on Android.

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"tailscale.com/tsnet"
)

// TailnetState is what the page shows.
type TailnetState struct {
	State   string   `json:"state"` // stopped, starting, needs_login, running, error
	AuthURL string   `json:"authUrl,omitempty"`
	IPs     []string `json:"ips,omitempty"`
	Error   string   `json:"error,omitempty"`
}

type Tailnet struct {
	dir      string
	hostname string

	mu     sync.Mutex
	srv    *tsnet.Server
	cancel context.CancelFunc
	st     TailnetState
}

func NewTailnet(dir, hostname string) *Tailnet {
	return &Tailnet{dir: dir, hostname: hostname, st: TailnetState{State: "stopped"}}
}

func (t *Tailnet) State() TailnetState {
	t.mu.Lock()
	defer t.mu.Unlock()
	s := t.st
	s.IPs = append([]string(nil), t.st.IPs...)
	return s
}

func (t *Tailnet) set(s TailnetState) {
	t.mu.Lock()
	t.st = s
	t.mu.Unlock()
}

// Start brings the node up in the background; State shows the progress and the sign-in link.
func (t *Tailnet) Start() error {
	t.mu.Lock()
	if t.srv != nil {
		t.mu.Unlock()
		return nil
	}
	if err := os.MkdirAll(t.dir, 0o700); err != nil {
		t.mu.Unlock()
		return err
	}
	_ = os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	logs := filepath.Join(t.dir, "logs")
	_ = os.MkdirAll(logs, 0o700)
	_ = os.Setenv("TS_LOGS_DIR", logs)
	srv := &tsnet.Server{
		Dir:      t.dir,
		Hostname: t.hostname,
		// Quiet: the node's own log lines can carry sign-in links.
		Logf:     func(string, ...any) {},
		UserLogf: func(string, ...any) {},
	}
	if err := srv.Start(); err != nil {
		t.mu.Unlock()
		t.set(TailnetState{State: "error", Error: err.Error()})
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.srv = srv
	t.cancel = cancel
	t.st = TailnetState{State: "starting"}
	t.mu.Unlock()
	go t.watch(ctx, srv)
	return nil
}

func (t *Tailnet) watch(ctx context.Context, srv *tsnet.Server) {
	lc, err := srv.LocalClient()
	if err != nil {
		t.set(TailnetState{State: "error", Error: err.Error()})
		return
	}
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		sctx, cancel := context.WithTimeout(ctx, 3*time.Second)
		st, err := lc.StatusWithoutPeers(sctx)
		cancel()
		if err == nil && st != nil {
			switch st.BackendState {
			case "Running":
				ips := make([]string, 0, len(st.TailscaleIPs))
				for _, ip := range st.TailscaleIPs {
					ips = append(ips, ip.String())
				}
				t.set(TailnetState{State: "running", IPs: ips})
			case "NeedsLogin", "NeedsMachineAuth":
				t.set(TailnetState{State: "needs_login", AuthURL: st.AuthURL})
			default:
				t.set(TailnetState{State: "starting"})
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}

// NodeID is this node's Tailscale id, which the host checks during pairing.
func (t *Tailnet) NodeID() (string, error) {
	t.mu.Lock()
	srv := t.srv
	t.mu.Unlock()
	if srv == nil {
		return "", errors.New("tailnet is not running")
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		return "", err
	}
	if st.Self == nil {
		return "", errors.New("node status unavailable")
	}
	if st.Self.ID != "" {
		return string(st.Self.ID), nil
	}
	if st.Self.NodeID != 0 {
		return strconv.FormatInt(int64(st.Self.NodeID), 10), nil
	}
	return "", errors.New("node id not assigned")
}

// Dial opens a connection over the tailnet; it fails until the node is signed in.
func (t *Tailnet) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	t.mu.Lock()
	srv := t.srv
	running := t.st.State == "running"
	t.mu.Unlock()
	if srv == nil || !running {
		return nil, errors.New("tailnet is not connected")
	}
	return srv.Dial(ctx, network, addr)
}

// HTTPClient sends requests through the tailnet.
func (t *Tailnet) HTTPClient() *http.Client {
	return &http.Client{Transport: &http.Transport{
		DialContext:       t.Dial,
		DisableKeepAlives: true,
	}}
}

// Logout forgets the node: stops it and deletes its state, so the next start asks for a sign-in again.
func (t *Tailnet) Logout() error {
	t.Stop()
	if err := os.RemoveAll(t.dir); err != nil {
		return fmt.Errorf("remove tailnet state: %w", err)
	}
	return nil
}

func (t *Tailnet) Stop() {
	t.mu.Lock()
	srv, cancel := t.srv, t.cancel
	t.srv, t.cancel = nil, nil
	t.st = TailnetState{State: "stopped"}
	t.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if srv != nil {
		_ = srv.Close()
	}
}
