package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unsafe"

	"github.com/wlynxg/anet"
	"tailscale.com/client/local"
	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netcheck"
	"tailscale.com/tsnet"
	"tailscale.com/types/logger"
)

const (
	// ErrNeedsLoginPrefix is the prefix used for errors when the tailnet control plane
	// has rejected the node key or indicated the device is revoked/needs authentication.
	ErrNeedsLoginPrefix = "NEEDS_LOGIN:"

	// Modes for PcnetBegin
	ModeRemoteDial = 0 // listens on 127.0.0.1:localPort, dials host:hostPort over tailnet
	ModeHostListen = 1 // listens on :hostPort over tailnet, forwards to 127.0.0.1:localPort
)

type StateInfo struct {
	State      string   `json:"state"`
	AuthURL    string   `json:"authUrl"`
	TailnetIPs []string `json:"tailnetIps"`
	LocalPort  int      `json:"localPort"`
	Error      string   `json:"error"`
}

type BeginConfig struct {
	StateDir   string
	Hostname   string
	Mode       int
	Host       string
	HostPort   int
	LocalPort  int
	ControlURL string
}

func init() {
	// Disable uploading diagnostic logs to log.tailscale.io
	envknob.SetNoLogsNoSupport()
}

// Config specifies tsnet parameters and loopback forwarding targets.
type Config struct {
	StateDir   string
	AuthKey    string
	ControlURL string
	Host       string
	HostPort   int
	LocalPort  int
	Hostname   string
}

type lifecycleState int

const (
	stateStopped lifecycleState = iota
	stateStarting
	stateRunning
	stateStopping
)

var (
	lifecycleMu      sync.Mutex
	lifecycleCond    = sync.NewCond(&lifecycleMu)
	mu               = &lifecycleMu
	state            lifecycleState = stateStopped
	startCancel      context.CancelFunc
	refreshCancel    context.CancelFunc
	// True while runBegin is inside srv.Start(); Stop waits for it, since closing a tsnet.Server
	// that is still starting can panic inside tsnet.
	beginStarting    bool

	activeServer     *tsnet.Server
	activeListener   net.Listener
	activeConns      = make(map[net.Conn]struct{})
	isRunning        bool
	lastError        string
	testHookBeforeUp func()

	targetPeerHostMu sync.Mutex
	targetPeerHost   string
	currentAuthKey   string

	lastDialMu        sync.Mutex
	lastDialError     string
	lastDialErrorTime string

	globalLogRing = newLogRingBuffer(200)
	currentStateStr   = "stopped"
	currentAuthURL    = ""
	currentTailnetIPs = []string{}
	currentBoundPort  = 0
	isLegacyStart     = false
)

type logRingBuffer struct {
	mu       sync.Mutex
	lines    []string
	maxLines int
	authKey  string
}

func newLogRingBuffer(maxLines int) *logRingBuffer {
	return &logRingBuffer{
		lines:    make([]string, 0, maxLines),
		maxLines: maxLines,
	}
}

var secretPatterns = []*regexp.Regexp{
	regexp.MustCompile(`tskey-[a-zA-Z0-9_\-\.\:\/]+`),
	regexp.MustCompile(`(?i)(auth[-_]?key|priv[-_]?key|private[-_]?key|token)[=:\s]+([a-zA-Z0-9_\-\+\/=]{8,})`),
	regexp.MustCompile(`(?i)(nodekey|machinekey|privkey):([a-f0-9]{32,64})`),
}

func sanitizeSecrets(msg string, authKey string) string {
	if authKey != "" {
		msg = strings.ReplaceAll(msg, authKey, "[REDACTED_AUTHKEY]")
	}
	for _, re := range secretPatterns {
		if re.NumSubexp() >= 2 {
			msg = re.ReplaceAllString(msg, "$1:[REDACTED_SECRET]")
		} else {
			msg = re.ReplaceAllString(msg, "[REDACTED_KEY]")
		}
	}
	return msg
}

func sanitizeLog(msg string, authKey string) string {
	clean := sanitizeSecrets(msg, authKey)
	if len(clean) > 500 {
		clean = clean[:500] + "..."
	}
	return clean
}

func (rb *logRingBuffer) setAuthKey(k string) {
	rb.mu.Lock()
	defer rb.mu.Unlock()
	rb.authKey = k
}

func (rb *logRingBuffer) append(msg string) {
	rb.mu.Lock()
	defer rb.mu.Unlock()
	clean := sanitizeLog(msg, rb.authKey)
	if len(rb.lines) >= rb.maxLines {
		rb.lines = rb.lines[1:]
	}
	rb.lines = append(rb.lines, clean)
}

func (rb *logRingBuffer) lastLines(n int) []string {
	rb.mu.Lock()
	defer rb.mu.Unlock()
	total := len(rb.lines)
	if total == 0 {
		return []string{}
	}
	start := total - n
	if start < 0 {
		start = 0
	}
	res := make([]string, total-start)
	copy(res, rb.lines[start:])
	return res
}

func setLastDialError(errStr string) {
	lastDialMu.Lock()
	defer lastDialMu.Unlock()
	lastDialError = errStr
	lastDialErrorTime = time.Now().UTC().Format(time.RFC3339)
}

func getLastDialErrorInfo() (string, string) {
	lastDialMu.Lock()
	defer lastDialMu.Unlock()
	return lastDialError, lastDialErrorTime
}

type InterfaceDiag struct {
	Name  string   `json:"name"`
	Up    bool     `json:"up"`
	Addrs []string `json:"addrs"`
}

func getReportedInterfaces() []InterfaceDiag {
	ifs, err := anet.Interfaces()
	if err != nil {
		return []InterfaceDiag{}
	}
	res := make([]InterfaceDiag, 0, len(ifs))
	for i := range ifs {
		ifi := &ifs[i]
		isUp := (ifi.Flags & net.FlagUp) != 0
		addrs, err := anet.InterfaceAddrsByInterface(ifi)
		var addrStrs []string
		if err == nil {
			for _, a := range addrs {
				addrStrs = append(addrStrs, a.String())
			}
		}
		res = append(res, InterfaceDiag{
			Name:  ifi.Name,
			Up:    isUp,
			Addrs: addrStrs,
		})
	}
	return res
}

func maskIPAddrPort(ap netip.AddrPort) string {
	if !ap.IsValid() {
		return ""
	}
	ip := ap.Addr()
	port := ap.Port()
	if ip.Is4() {
		octets := strings.Split(ip.String(), ".")
		if len(octets) == 4 {
			return fmt.Sprintf("%s.*.*.%s:%d", octets[0], octets[3], port)
		}
	} else if ip.Is6() {
		parts := strings.Split(ip.String(), ":")
		if len(parts) > 2 {
			return fmt.Sprintf("%s:****:%s:%d", parts[0], parts[len(parts)-1], port)
		}
	}
	return fmt.Sprintf("masked:%d", port)
}

type PeerHostDiag struct {
	Online        bool   `json:"online"`
	Relay         string `json:"relay,omitempty"`
	CurAddr       string `json:"curAddr,omitempty"`
	LastHandshake string `json:"lastHandshake,omitempty"`
	RxBytes       int64  `json:"rxBytes"`
	TxBytes       int64  `json:"txBytes"`
}

type NetcheckDiag struct {
	UDP           bool        `json:"udp"`
	IPv4          bool        `json:"ipv4"`
	IPv6          bool        `json:"ipv6"`
	GlobalV4      string      `json:"globalV4,omitempty"`
	PreferredDERP int         `json:"preferredDERP"`
	DERPLatency   map[int]int `json:"derpLatency,omitempty"`
}

type Diagnostics struct {
	BackendState      string          `json:"backendState"`
	HealthWarnings    []string        `json:"healthWarnings"`
	SelfOnline        bool            `json:"selfOnline"`
	PreferredDERP     int             `json:"preferredDERP"`
	DERPLatency       map[int]int     `json:"derpLatency,omitempty"`
	UDP               bool            `json:"udp"`
	Netcheck          *NetcheckDiag   `json:"netcheck,omitempty"`
	PeerHost          *PeerHostDiag   `json:"peerHost,omitempty"`
	LastDialError     string          `json:"lastDialError,omitempty"`
	LastDialErrorTime string          `json:"lastDialErrorTime,omitempty"`
	Interfaces        []InterfaceDiag `json:"interfaces"`
	Logs              []string        `json:"logs"`
}

func networkStateRefreshLoop(ctx context.Context, srv *tsnet.Server) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			_, _ = anet.Interfaces()
			if srv != nil && srv.Sys() != nil {
				if nm := srv.Sys().NetMon.Get(); nm != nil {
					nm.Poll()
				}
			}
		}
	}
}

func GetDiagnostics() (*Diagnostics, error) {
	lifecycleMu.Lock()
	srv := activeServer
	st := state
	lifecycleMu.Unlock()

	targetPeerHostMu.Lock()
	peerHostTarget := targetPeerHost
	targetPeerHostMu.Unlock()

	diag := &Diagnostics{
		BackendState:   "Stopped",
		HealthWarnings: []string{},
		Interfaces:     getReportedInterfaces(),
		Logs:           globalLogRing.lastLines(40),
	}

	diag.LastDialError, diag.LastDialErrorTime = getLastDialErrorInfo()

	switch st {
	case stateStarting:
		diag.BackendState = "Starting"
	case stateStopping:
		diag.BackendState = "Stopping"
	case stateStopped:
		diag.BackendState = "Stopped"
	case stateRunning:
		diag.BackendState = "Running"
	}

	if srv == nil {
		return diag, nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	lc, err := srv.LocalClient()
	if err == nil && lc != nil {
		status, err := lc.Status(ctx)
		if err == nil && status != nil {
			if status.BackendState != "" {
				diag.BackendState = status.BackendState
			}
			if status.Health != nil {
				diag.HealthWarnings = status.Health
			}
			if status.Self != nil {
				diag.SelfOnline = status.Self.Online
			}
			if peerHostTarget != "" && status.Peer != nil {
				for _, peer := range status.Peer {
					if peer == nil {
						continue
					}
					if peerMatchesTarget(peer, peerHostTarget) {
						pDiag := &PeerHostDiag{
							Online:  peer.Online,
							Relay:   peer.Relay,
							CurAddr: peer.CurAddr,
							RxBytes: peer.RxBytes,
							TxBytes: peer.TxBytes,
						}
						if !peer.LastHandshake.IsZero() {
							pDiag.LastHandshake = peer.LastHandshake.UTC().Format(time.RFC3339)
						}
						diag.PeerHost = pDiag
						break
					}
				}
			}
		}
	}

	var report *netcheck.Report
	if srv.Sys() != nil && srv.Sys().MagicSock.Get() != nil {
		ms := srv.Sys().MagicSock.Get()
		report = ms.GetLastNetcheckReport(ctx)
	}

	if report == nil && lc != nil {
		dm, dmErr := lc.CurrentDERPMap(ctx)
		if dmErr == nil && dm != nil && len(dm.Regions) > 0 {
			if srv.Sys() != nil {
				netMon := srv.Sys().NetMon.Get()
				nc := &netcheck.Client{
					NetMon: netMon,
					Logf:   logger.Discard,
				}
				r, err := nc.GetReport(ctx, dm, nil)
				if err == nil {
					report = r
				}
			}
		}
	}

	if report != nil {
		diag.UDP = report.UDP
		diag.PreferredDERP = int(report.PreferredDERP)
		latMap := make(map[int]int)
		for regID, d := range report.RegionLatency {
			latMap[int(regID)] = int(d.Milliseconds())
		}
		diag.DERPLatency = latMap

		netDiag := &NetcheckDiag{
			UDP:           report.UDP,
			IPv4:          report.IPv4,
			IPv6:          report.IPv6,
			PreferredDERP: int(report.PreferredDERP),
			DERPLatency:   latMap,
		}
		if report.GlobalV4.IsValid() {
			netDiag.GlobalV4 = maskIPAddrPort(report.GlobalV4)
		}
		diag.Netcheck = netDiag
	} else if srv.Sys() != nil && srv.Sys().MagicSock.Get() != nil {
		ms := srv.Sys().MagicSock.Get()
		lats := ms.GetDERPRegionLatency()
		if len(lats) > 0 {
			latMap := make(map[int]int)
			for regID, d := range lats {
				latMap[int(regID)] = int(d.Milliseconds())
			}
			diag.DERPLatency = latMap
		}
	}

	return diag, nil
}

// peerMatchesTarget reports whether peer is the host this node was told to reach (by tailnet IP,
// hostname or MagicDNS name).
func peerMatchesTarget(peer *ipnstate.PeerStatus, target string) bool {
	if target == "" {
		return false
	}
	for _, ip := range peer.TailscaleIPs {
		if ip.String() == target {
			return true
		}
	}
	return peer.HostName == target || strings.TrimSuffix(peer.DNSName, ".") == target
}

// PeerInfo is one device of the tailnet as this node's network map shows it: the same list, and the
// same online state, as the Tailscale admin console's device list.
type PeerInfo struct {
	HostName string   `json:"hostName"`
	DNSName  string   `json:"dnsName,omitempty"`
	OS       string   `json:"os,omitempty"`
	IPs      []string `json:"ips"`
	Online   bool     `json:"online"`
	LastSeen string   `json:"lastSeen,omitempty"`
	// Target marks the host this node dials (the home phone, seen from the remote).
	Target bool `json:"target"`
}

type PeersInfo struct {
	BackendState string     `json:"backendState"`
	Peers        []PeerInfo `json:"peers"`
}

// GetPeers lists the tailnet's other devices with their online state. The state comes from the
// coordination server, so asking costs no traffic to the devices themselves.
func GetPeers(ctx context.Context) (*PeersInfo, error) {
	lifecycleMu.Lock()
	srv := activeServer
	lifecycleMu.Unlock()

	targetPeerHostMu.Lock()
	target := targetPeerHost
	targetPeerHostMu.Unlock()

	out := &PeersInfo{BackendState: "Stopped", Peers: []PeerInfo{}}
	if srv == nil {
		return out, nil
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return nil, err
	}
	status, err := lc.Status(ctx)
	if err != nil {
		return nil, err
	}
	out.BackendState = status.BackendState
	for _, peer := range status.Peer {
		if peer == nil {
			continue
		}
		info := PeerInfo{
			HostName: peer.HostName,
			DNSName:  strings.TrimSuffix(peer.DNSName, "."),
			OS:       peer.OS,
			IPs:      []string{},
			Online:   peer.Online,
			Target:   peerMatchesTarget(peer, target),
		}
		for _, ip := range peer.TailscaleIPs {
			info.IPs = append(info.IPs, ip.String())
		}
		if !peer.Online && !peer.LastSeen.IsZero() {
			info.LastSeen = peer.LastSeen.UTC().Format(time.RFC3339)
		}
		out.Peers = append(out.Peers, info)
	}
	sort.Slice(out.Peers, func(i, j int) bool {
		return out.Peers[i].HostName < out.Peers[j].HostName
	})
	return out, nil
}

func GetDiagnosticsJSON() (string, error) {
	diag, err := GetDiagnostics()
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(diag)
	if err != nil {
		return "", err
	}
	targetPeerHostMu.Lock()
	curAuth := currentAuthKey
	targetPeerHostMu.Unlock()
	str := string(b)
	str = sanitizeSecrets(str, curAuth)
	return str, nil
}


func setLastError(err string) {
	lifecycleMu.Lock()
	lastError = err
	lifecycleMu.Unlock()
}

// ClassifyStartupError categorizes a startup failure based on the backend state,
// error returned, and whether an authkey was provided.
// It returns an error prefixed with ErrNeedsLoginPrefix if and only if the device
// was revoked or rejected by the control plane (NeedsLogin/NeedsMachineAuth/invalid authkey).
// Transient network errors (timeouts, connection refused, DNS issues while in Starting/NoState)
// are returned as-is and MUST NOT be classified as revoked.
func ClassifyStartupError(authKey string, backendState string, err error) error {
	if err == nil && (backendState == "Running" || backendState == "") {
		return nil
	}
	if err != nil && strings.Contains(err.Error(), "panic") {
		return err
	}
	if authKey == "" {
		if backendState == "NeedsLogin" || backendState == "NeedsMachineAuth" ||
			strings.Contains(strings.ToLower(backendState), "invalid authkey") ||
			strings.Contains(strings.ToLower(backendState), "node key expired") {
			return fmt.Errorf("%s backend in state %s", ErrNeedsLoginPrefix, backendState)
		}
		if err != nil {
			errStr := err.Error()
			lower := strings.ToLower(errStr)
			if strings.Contains(lower, "invalid authkey") ||
				strings.Contains(lower, "node key expired") ||
				strings.Contains(lower, "needslogin") ||
				strings.Contains(lower, "needsmachineauth") {
				return fmt.Errorf("%s %s", ErrNeedsLoginPrefix, errStr)
			}
		}
	}

	return err
}

// IsNeedsLogin checks whether an error indicates the node needs re-authentication / was revoked.
func IsNeedsLogin(err error) bool {
	if err == nil {
		return false
	}
	return strings.HasPrefix(err.Error(), ErrNeedsLoginPrefix)
}

// LastError returns the most recent error string recorded by pcnet.
func LastError() string {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	return lastError
}
// Start boots the userspace tsnet node, binds a local loopback TCP listener,
// and proxies incoming TCP streams to Config.Host:Config.HostPort over tsnet.
func Start(ctx context.Context, cfg Config) (int, error) {
	lifecycleMu.Lock()
	if state == stateRunning {
		lifecycleMu.Unlock()
		return 0, errors.New("pcnet is already running")
	}
	if state == stateStarting {
		lifecycleMu.Unlock()
		return 0, errors.New("pcnet is already starting")
	}
	if state == stateStopping {
		lifecycleMu.Unlock()
		return 0, errors.New("pcnet is stopping")
	}

	state = stateStarting
	currentStateStr = "starting"
	isLegacyStart = true
	startCtx, cancel := context.WithCancel(ctx)
	startCancel = cancel
	lastError = ""
	if cfg.StateDir == "" {
		state = stateStopped
		startCancel = nil
		lastError = "stateDir is required"
		lifecycleCond.Broadcast()
		lifecycleMu.Unlock()
		return 0, errors.New("stateDir is required")
	}

	stateDir := filepath.Clean(cfg.StateDir)
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		state = stateStopped
		startCancel = nil
		lastError = fmt.Sprintf("create stateDir failed: %v", err)
		lifecycleCond.Broadcast()
		lifecycleMu.Unlock()
		return 0, fmt.Errorf("create stateDir failed: %w", err)
	}

	logsDir := filepath.Join(stateDir, "logs")
	if err := os.MkdirAll(logsDir, 0700); err != nil {
		state = stateStopped
		startCancel = nil
		lastError = fmt.Sprintf("create logsDir failed: %v", err)
		lifecycleCond.Broadcast()
		lifecycleMu.Unlock()
		return 0, fmt.Errorf("create logsDir failed: %w", err)
	}
	_ = os.Setenv("TS_LOGS_DIR", logsDir)

	cacheDir := filepath.Join(stateDir, "cache")
	_ = os.MkdirAll(cacheDir, 0700)

	configDir := filepath.Join(stateDir, "config")
	_ = os.MkdirAll(configDir, 0700)

	if os.Getenv("HOME") == "" {
		_ = os.Setenv("HOME", stateDir)
	}
	if os.Getenv("XDG_CACHE_HOME") == "" {
		_ = os.Setenv("XDG_CACHE_HOME", cacheDir)
	}
	if os.Getenv("XDG_CONFIG_HOME") == "" {
		_ = os.Setenv("XDG_CONFIG_HOME", configDir)
	}
	if os.Getenv("TMPDIR") == "" {
		tmpDir := filepath.Join(stateDir, "tmp")
		if err := os.MkdirAll(tmpDir, 0700); err == nil {
			_ = os.Setenv("TMPDIR", tmpDir)
		}
	}

	_ = os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	envknob.SetNoLogsNoSupport()

	hostname := cfg.Hostname
	if hostname == "" {
		hostname = "pcdash-remote"
	}

	targetPeerHostMu.Lock()
	targetPeerHost = cfg.Host
	currentAuthKey = cfg.AuthKey
	targetPeerHostMu.Unlock()
	globalLogRing.setAuthKey(cfg.AuthKey)

	srv := &tsnet.Server{
		Dir:        stateDir,
		Hostname:   hostname,
		AuthKey:    cfg.AuthKey,
		ControlURL: cfg.ControlURL,
		Ephemeral:  false,
		Logf: func(format string, args ...any) {
			globalLogRing.append(fmt.Sprintf(format, args...))
		},
		UserLogf: func(format string, args ...any) {
			globalLogRing.append(fmt.Sprintf(format, args...))
		},
	}
	lifecycleMu.Unlock()

	cleanedUp := false
	defer func() {
		if !cleanedUp {
			lifecycleMu.Lock()
			if state == stateStarting {
				state = stateStopped
				isRunning = false
				startCancel = nil
				srv.Close()
				lifecycleCond.Broadcast()
			}
			lifecycleMu.Unlock()
		}
	}()

	startupTimeout := 20 * time.Second
	if d, ok := startCtx.Deadline(); ok {
		if rem := time.Until(d); rem > 0 && rem < startupTimeout {
			startupTimeout = rem
		}
	}

	upCtx, upCancel := context.WithTimeout(startCtx, startupTimeout)
	defer upCancel()

	type upResult struct {
		status *ipnstate.Status
		err    error
	}
	upCh := make(chan upResult, 1)

	go func() {
		defer func() {
			if r := recover(); r != nil {
				upCh <- upResult{err: fmt.Errorf("panic during tsnet up: %v", r)}
			}
		}()
		if testHookBeforeUp != nil {
			testHookBeforeUp()
		}
		st, err := srv.Up(upCtx)
		upCh <- upResult{status: st, err: err}
	}()

	var (
		upErr        error
		detectedNeed string
	)

	if cfg.AuthKey == "" {
		needsLoginCh := make(chan string, 1)
		go func() {
			lc, err := srv.LocalClient()
			if err != nil || lc == nil {
				return
			}
			watcher, err := lc.WatchIPNBus(upCtx, ipn.NotifyInitialState)
			if err != nil || watcher == nil {
				return
			}
			defer watcher.Close()

			var (
				loginTimer   *time.Timer
				loginTimerCh <-chan time.Time
				lastState    string
			)
			defer func() {
				if loginTimer != nil {
					loginTimer.Stop()
				}
			}()

			notifyCh := make(chan ipn.Notify, 8)
			errCh := make(chan error, 1)
			go func() {
				for {
					n, err := watcher.Next()
					if err != nil {
						errCh <- err
						return
					}
					notifyCh <- n
				}
			}()

			for {
				select {
				case <-upCtx.Done():
					return
				case <-loginTimerCh:
					if lastState == "NeedsLogin" || lastState == "NeedsMachineAuth" {
						select {
						case needsLoginCh <- lastState:
						default:
						}
						upCancel()
						return
					}
				case <-errCh:
					return
				case n := <-notifyCh:
					if n.ErrMessage != nil {
						msg := *n.ErrMessage
						lower := strings.ToLower(msg)
						if strings.Contains(lower, "invalid authkey") ||
							strings.Contains(lower, "node key expired") {
							select {
							case needsLoginCh <- msg:
							default:
							}
							upCancel()
							return
						}
					}
					if st := n.State; st != nil {
						lastState = st.String()
						if *st == ipn.Running {
							if loginTimer != nil {
								loginTimer.Stop()
								loginTimer = nil
								loginTimerCh = nil
							}
							return
						}
						if *st == ipn.NeedsLogin || *st == ipn.NeedsMachineAuth {
							if loginTimer == nil {
								loginTimer = time.NewTimer(3 * time.Second)
								loginTimerCh = loginTimer.C
							}
						} else {
							if loginTimer != nil {
								loginTimer.Stop()
								loginTimer = nil
								loginTimerCh = nil
							}
						}
					}
				}
			}
		}()

		select {
		case reason := <-needsLoginCh:
			detectedNeed = reason
			res := <-upCh
			upErr = res.err
		case res := <-upCh:
			upErr = res.err
			select {
			case reason := <-needsLoginCh:
				detectedNeed = reason
			default:
			}
		}
	} else {
		res := <-upCh
		upErr = res.err
	}

	if upErr != nil {
		finalBackendState := detectedNeed
		if finalBackendState == "" {
			if lc, err := srv.LocalClient(); err == nil && lc != nil {
				ctxStatus, cancelStatus := context.WithTimeout(context.Background(), 1*time.Second)
				if st, err := lc.Status(ctxStatus); err == nil && st != nil {
					finalBackendState = st.BackendState
				}
				cancelStatus()
			}
		}

		classifiedErr := ClassifyStartupError(cfg.AuthKey, finalBackendState, upErr)
		if classifiedErr != nil {
			upErr = classifiedErr
		}
	}

	var (
		ln           net.Listener
		lnErr        error
		assignedPort int
	)

	if upErr == nil {
		ln, lnErr = net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", cfg.LocalPort))
		if lnErr == nil {
			assignedPort = ln.Addr().(*net.TCPAddr).Port
		}
	}

	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()

	startCancel = nil

	// Start only publishes if it was not stopped while starting
	if startCtx.Err() != nil || state != stateStarting {
		cleanedUp = true
		if ln != nil {
			ln.Close()
		}
		srv.Close()
		state = stateStopped
		isRunning = false
		lifecycleCond.Broadcast()
		return 0, fmt.Errorf("start aborted: %w", context.Canceled)
	}

	if upErr != nil {
		cleanedUp = true
		srv.Close()
		state = stateStopped
		isRunning = false
		if IsNeedsLogin(upErr) {
			lastError = upErr.Error()
		} else {
			lastError = fmt.Sprintf("tsnet startup failed: %v", upErr)
		}
		lifecycleCond.Broadcast()
		if IsNeedsLogin(upErr) {
			return 0, upErr
		}
		return 0, fmt.Errorf("tsnet startup failed: %w", upErr)
	}

	if lnErr != nil {
		cleanedUp = true
		srv.Close()
		state = stateStopped
		isRunning = false
		lastError = "loopback listen failed"
		lifecycleCond.Broadcast()
		return 0, fmt.Errorf("failed to listen on loopback: %w", lnErr)
	}

	activeServer = srv
	activeListener = ln
	isRunning = true
	state = stateRunning
	currentStateStr = "running"
	currentBoundPort = assignedPort
	cleanedUp = true
	refCtx, refCancel := context.WithCancel(context.Background())
	refreshCancel = refCancel
	go networkStateRefreshLoop(refCtx, srv)

	lifecycleCond.Broadcast()
	go forwardLoop(srv, ln, cfg.Host, cfg.HostPort)

	return assignedPort, nil
}

func forwardLoop(srv *tsnet.Server, ln net.Listener, targetHost string, targetPort int) {
	for {
		clientConn, err := ln.Accept()
		if err != nil {
			// Listener was closed
			return
		}

		lifecycleMu.Lock()
		if !isRunning || state != stateRunning {
			lifecycleMu.Unlock()
			clientConn.Close()
			return
		}
		activeConns[clientConn] = struct{}{}
		lifecycleMu.Unlock()

		go func(client net.Conn) {
			defer func() {
				lifecycleMu.Lock()
				delete(activeConns, client)
				lifecycleMu.Unlock()
				client.Close()
			}()

			dialCtx, dialCancel := context.WithTimeout(context.Background(), 20*time.Second)
			defer dialCancel()

			targetAddr := net.JoinHostPort(targetHost, strconv.Itoa(targetPort))
			remoteConn, err := srv.Dial(dialCtx, "tcp", targetAddr)
			if err != nil {
				setLastDialError(fmt.Sprintf("dial %s: %v", targetAddr, err))
				return
			}
			defer func() {
				lifecycleMu.Lock()
				delete(activeConns, remoteConn)
				lifecycleMu.Unlock()
				remoteConn.Close()
			}()

			lifecycleMu.Lock()
			if !isRunning || state != stateRunning {
				lifecycleMu.Unlock()
				return
			}
			activeConns[remoteConn] = struct{}{}
			lifecycleMu.Unlock()

			errc := make(chan error, 2)
			go func() {
				_, err := io.Copy(remoteConn, client)
				errc <- err
			}()
			go func() {
				_, err := io.Copy(client, remoteConn)
				errc <- err
			}()
			<-errc
		}(clientConn)
	}
}

func hostForwardLoop(srv *tsnet.Server, ln net.Listener, targetLocalPort int) {
	for {
		clientConn, err := ln.Accept()
		if err != nil {
			return
		}

		lifecycleMu.Lock()
		if !isRunning || state != stateRunning {
			lifecycleMu.Unlock()
			clientConn.Close()
			return
		}
		if len(activeConns) >= 64 {
			lifecycleMu.Unlock()
			clientConn.Close()
			continue
		}
		activeConns[clientConn] = struct{}{}
		lifecycleMu.Unlock()

		go func(remote net.Conn) {
			defer func() {
				lifecycleMu.Lock()
				delete(activeConns, remote)
				lifecycleMu.Unlock()
				remote.Close()
			}()

			dialCtx, dialCancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer dialCancel()

			var d net.Dialer
			localConn, err := d.DialContext(dialCtx, "tcp", fmt.Sprintf("127.0.0.1:%d", targetLocalPort))
			if err != nil {
				setLastDialError(fmt.Sprintf("dial local 127.0.0.1:%d: %v", targetLocalPort, err))
				return
			}
			defer func() {
				lifecycleMu.Lock()
				delete(activeConns, localConn)
				lifecycleMu.Unlock()
				localConn.Close()
			}()

			lifecycleMu.Lock()
			if !isRunning || state != stateRunning {
				lifecycleMu.Unlock()
				return
			}
			activeConns[localConn] = struct{}{}
			lifecycleMu.Unlock()

			errc := make(chan error, 2)
			go func() {
				_, err := io.Copy(localConn, remote)
				errc <- err
			}()
			go func() {
				_, err := io.Copy(remote, localConn)
				errc <- err
			}()
			<-errc
		}(clientConn)
	}
}

// Stop shuts down the local listener, closes active connections, and closes the tsnet server.
func Stop() error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()

	for state == stateStopping {
		lifecycleCond.Wait()
	}
	if state == stateStarting {
		state = stateStopping
		if startCancel != nil {
			startCancel()
			startCancel = nil
		}
		if isLegacyStart {
			for state == stateStopping {
				lifecycleCond.Wait()
			}
		}
	}

	if state == stateStopped && activeServer == nil && activeListener == nil {
		isRunning = false
		currentStateStr = "stopped"
		currentAuthURL = ""
		currentTailnetIPs = []string{}
		currentBoundPort = 0
		return nil
	}

	state = stateStopping
	for beginStarting {
		lifecycleCond.Wait()
	}

	ln := activeListener
	srv := activeServer
	conns := make([]net.Conn, 0, len(activeConns))
	for c := range activeConns {
		conns = append(conns, c)
	}
	activeConns = make(map[net.Conn]struct{})
	activeListener = nil
	activeServer = nil
	isRunning = false
	currentStateStr = "stopped"
	currentAuthURL = ""
	currentTailnetIPs = []string{}
	currentBoundPort = 0
	if refreshCancel != nil {
		refreshCancel()
		refreshCancel = nil
	}
	var firstErr error
	if ln != nil {
		if err := ln.Close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}

	for _, c := range conns {
		c.Close()
	}

	if srv != nil {
		if err := srv.Close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}

	state = stateStopped
	lifecycleCond.Broadcast()

	return firstErr
}

// Running reports whether the tsnet forwarder is currently active.
func Running() bool {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	return isRunning && state == stateRunning
}

// NodeID retrieves the Tailscale Node ID of the active tsnet node.
func NodeID(ctx context.Context) (string, error) {
	lifecycleMu.Lock()
	srv := activeServer
	running := isRunning && state == stateRunning
	lifecycleMu.Unlock()

	if !running || srv == nil {
		return "", errors.New("pcnet is not running")
	}

	lc, err := srv.LocalClient()
	if err != nil {
		return "", fmt.Errorf("failed to get local client: %w", err)
	}

	status, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		return "", fmt.Errorf("failed to get status: %w", err)
	}

	if status.Self == nil {
		return "", errors.New("node status unavailable")
	}

	if status.Self.ID != "" {
		return string(status.Self.ID), nil
	}
	if status.Self.NodeID != 0 {
		return strconv.FormatInt(int64(status.Self.NodeID), 10), nil
	}

	return "", errors.New("node id not assigned")
}

func CurrentState() StateInfo {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()

	st := StateInfo{
		State:      currentStateStr,
		AuthURL:    "",
		TailnetIPs: []string{},
		LocalPort:  0,
		Error:      "",
	}

	if len(currentTailnetIPs) > 0 {
		st.TailnetIPs = make([]string, len(currentTailnetIPs))
		copy(st.TailnetIPs, currentTailnetIPs)
	}

	if currentStateStr == "needs_login" {
		st.AuthURL = currentAuthURL
		st.LocalPort = currentBoundPort
	} else if currentStateStr == "running" {
		st.LocalPort = currentBoundPort
	} else if currentStateStr == "starting" {
		st.LocalPort = currentBoundPort
	} else if currentStateStr == "error" {
		st.Error = lastError
	}

	return st
}

func pcnetStateInternal() string {
	st := CurrentState()
	data, err := json.Marshal(st)
	if err != nil {
		return `{"state":"error","authUrl":"","tailnetIps":[],"localPort":0,"error":"failed to marshal state"}`
	}
	return string(data)
}

func Begin(cfg BeginConfig) error {
	lifecycleMu.Lock()
	if state == stateRunning {
		lifecycleMu.Unlock()
		return errors.New("pcnet is already running")
	}
	if state == stateStarting {
		lifecycleMu.Unlock()
		return errors.New("pcnet is already starting")
	}
	if state == stateStopping {
		lifecycleMu.Unlock()
		return errors.New("pcnet is stopping")
	}

	if cfg.StateDir == "" {
		lastError = "stateDir is required"
		lifecycleMu.Unlock()
		return errors.New("stateDir is required")
	}

	if cfg.Mode != ModeRemoteDial && cfg.Mode != ModeHostListen {
		lastError = fmt.Sprintf("invalid mode: %d", cfg.Mode)
		lifecycleMu.Unlock()
		return fmt.Errorf("invalid mode: %d", cfg.Mode)
	}

	var (
		ln        net.Listener
		boundPort int
	)
	if cfg.Mode == ModeRemoteDial {
		if cfg.LocalPort > 0 || (cfg.Host != "" && cfg.HostPort > 0) {
			var err error
			ln, err = net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", cfg.LocalPort))
			if err != nil {
				lastError = fmt.Sprintf("listen on loopback port %d failed: %v", cfg.LocalPort, err)
				lifecycleMu.Unlock()
				return fmt.Errorf("listen on loopback port %d failed: %w", cfg.LocalPort, err)
			}
			boundPort = ln.Addr().(*net.TCPAddr).Port
		}
	}

	stateDir := filepath.Clean(cfg.StateDir)
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		if ln != nil {
			ln.Close()
		}
		lastError = fmt.Sprintf("create stateDir failed: %v", err)
		lifecycleMu.Unlock()
		return fmt.Errorf("create stateDir failed: %w", err)
	}

	logsDir := filepath.Join(stateDir, "logs")
	_ = os.MkdirAll(logsDir, 0700)
	_ = os.Setenv("TS_LOGS_DIR", logsDir)

	cacheDir := filepath.Join(stateDir, "cache")
	_ = os.MkdirAll(cacheDir, 0700)

	configDir := filepath.Join(stateDir, "config")
	_ = os.MkdirAll(configDir, 0700)

	if os.Getenv("HOME") == "" {
		_ = os.Setenv("HOME", stateDir)
	}
	if os.Getenv("XDG_CACHE_HOME") == "" {
		_ = os.Setenv("XDG_CACHE_HOME", cacheDir)
	}
	if os.Getenv("XDG_CONFIG_HOME") == "" {
		_ = os.Setenv("XDG_CONFIG_HOME", configDir)
	}
	if os.Getenv("TMPDIR") == "" {
		tmpDir := filepath.Join(stateDir, "tmp")
		if err := os.MkdirAll(tmpDir, 0700); err == nil {
			_ = os.Setenv("TMPDIR", tmpDir)
		}
	}

	_ = os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	envknob.SetNoLogsNoSupport()

	hostname := cfg.Hostname
	if hostname == "" {
		if cfg.Mode == ModeHostListen {
			hostname = "pcdash-host"
		} else {
			hostname = "pcdash-remote"
		}
	}

	targetPeerHostMu.Lock()
	targetPeerHost = cfg.Host
	currentAuthKey = ""
	targetPeerHostMu.Unlock()
	globalLogRing.setAuthKey("")

	srv := &tsnet.Server{
		Dir:        stateDir,
		Hostname:   hostname,
		ControlURL: cfg.ControlURL,
		Ephemeral:  false,
		Logf: func(format string, args ...any) {
			globalLogRing.append(fmt.Sprintf(format, args...))
		},
		UserLogf: func(format string, args ...any) {
			globalLogRing.append(fmt.Sprintf(format, args...))
		},
	}

	activeServer = srv
	activeListener = ln
	currentBoundPort = boundPort
	currentStateStr = "starting"
	currentAuthURL = ""
	currentTailnetIPs = []string{}
	lastError = ""
	state = stateStarting
	isRunning = false
	isLegacyStart = false

	bgCtx, cancel := context.WithCancel(context.Background())
	startCancel = cancel
	beginStarting = true

	lifecycleMu.Unlock()

	go runBegin(bgCtx, srv, cfg, ln, boundPort)

	return nil
}

func runBegin(ctx context.Context, srv *tsnet.Server, cfg BeginConfig, ln net.Listener, boundPort int) {
	err := srv.Start()
	lifecycleMu.Lock()
	beginStarting = false
	lifecycleCond.Broadcast()
	if err != nil {
		if state != stateStopping && state != stateStopped {
			currentStateStr = "error"
			lastError = fmt.Sprintf("tsnet start failed: %v", err)
			state = stateStopped
			isRunning = false
			if ln != nil {
				ln.Close()
				activeListener = nil
			}
			lifecycleCond.Broadcast()
		}
		lifecycleMu.Unlock()
		return
	}
	lifecycleMu.Unlock()

	var lc *local.Client
	for {
		if ctx.Err() != nil {
			return
		}
		var err error
		lc, err = srv.LocalClient()
		if err == nil && lc != nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	checkStatus := func() bool {
		stCtx, stCancel := context.WithTimeout(ctx, 2*time.Second)
		defer stCancel()
		st, err := lc.StatusWithoutPeers(stCtx)
		if err == nil && st != nil {
			return applyStatus(ctx, srv, st.BackendState, st.AuthURL, st.TailscaleIPs, cfg, ln, boundPort)
		}
		return false
	}

	checkStatus()

	watcher, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyInitialStatus)
	if err != nil {
		ticker := time.NewTicker(200 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				checkStatus()
			}
		}
	}
	defer watcher.Close()

	notifyCh := make(chan ipn.Notify, 16)
	errCh := make(chan error, 1)

	go func() {
		for {
			n, err := watcher.Next()
			if err != nil {
				select {
				case errCh <- err:
				default:
				}
				return
			}
			select {
			case notifyCh <- n:
			case <-ctx.Done():
				return
			}
		}
	}()

	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			checkStatus()
		case n := <-notifyCh:
			var authURL string
			if n.BrowseToURL != nil {
				authURL = *n.BrowseToURL
			}
			if n.State != nil {
				bState := n.State.String()
				if (bState == "NeedsLogin" || bState == "NeedsMachineAuth") && authURL == "" {
					checkStatus()
				} else {
					applyStatus(ctx, srv, bState, authURL, nil, cfg, ln, boundPort)
				}
			} else if authURL != "" {
				applyStatus(ctx, srv, "NeedsLogin", authURL, nil, cfg, ln, boundPort)
			}
		case <-errCh:
			for {
				select {
				case <-ctx.Done():
					return
				case <-ticker.C:
					checkStatus()
				}
			}
		}
	}
}

func applyStatus(ctx context.Context, srv *tsnet.Server, backendState string, authURL string, ips []netip.Addr, cfg BeginConfig, ln net.Listener, boundPort int) bool {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()

	if state == stateStopping || state == stateStopped {
		return false
	}

	if backendState == "NeedsLogin" || backendState == "NeedsMachineAuth" {
		currentStateStr = "needs_login"
		if authURL != "" {
			currentAuthURL = authURL
		}
		currentTailnetIPs = []string{}
		isRunning = false
		return false
	}

	if backendState == "Running" {
		wasRunning := (state == stateRunning && isRunning)
		currentStateStr = "running"
		currentAuthURL = ""
		if len(ips) > 0 {
			ipStrs := make([]string, 0, len(ips))
			for _, ip := range ips {
				ipStrs = append(ipStrs, ip.String())
			}
			currentTailnetIPs = ipStrs
		} else if len(currentTailnetIPs) == 0 {
			if ip4, ip6 := srv.TailscaleIPs(); ip4.IsValid() {
				ipStrs := []string{ip4.String()}
				if ip6.IsValid() {
					ipStrs = append(ipStrs, ip6.String())
				}
				currentTailnetIPs = ipStrs
			}
		}
		if !wasRunning {
			state = stateRunning
			isRunning = true
			lifecycleCond.Broadcast()

			if cfg.Mode == ModeRemoteDial && ln != nil && cfg.Host != "" && cfg.HostPort > 0 {
				go forwardLoop(srv, ln, cfg.Host, cfg.HostPort)
			} else if cfg.Mode == ModeHostListen && cfg.HostPort > 0 && cfg.LocalPort > 0 {
				go func() {
					hostLn, err := srv.Listen("tcp", fmt.Sprintf(":%d", cfg.HostPort))
					if err != nil {
						lifecycleMu.Lock()
						currentStateStr = "error"
						lastError = fmt.Sprintf("listen on tailnet :%d failed: %v", cfg.HostPort, err)
						isRunning = false
						lifecycleMu.Unlock()
						return
					}
					lifecycleMu.Lock()
					activeListener = hostLn
					lifecycleMu.Unlock()
					go hostForwardLoop(srv, hostLn, cfg.LocalPort)
				}()
			}

			refCtx, refCancel := context.WithCancel(context.Background())
			refreshCancel = refCancel
			go networkStateRefreshLoop(refCtx, srv)
		}
		return true
	}

	if backendState == "Starting" && currentStateStr != "needs_login" {
		currentStateStr = "starting"
	}

	return false
}

func pcnetStartInternal(stateDir, authKey, host string, hostPort, localPort int) (retPort int) {
	defer func() {
		if r := recover(); r != nil {
			errStr := fmt.Sprintf("panic in PcnetStart: %v", r)
			setLastError(errStr)
			retPort = -1
		}
	}()

	cfg := Config{
		StateDir:  stateDir,
		AuthKey:   authKey,
		Host:      host,
		HostPort:  hostPort,
		LocalPort: localPort,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	port, err := Start(ctx, cfg)
	if err != nil {
		setLastError(err.Error())
		return -1
	}

	return port
}

//export PcnetStart
func PcnetStart(cStateDir, cAuthKey, cHost *C.char, cHostPort, cLocalPort C.int) (retPort C.int) {
	defer func() {
		if r := recover(); r != nil {
			errStr := fmt.Sprintf("panic in PcnetStart: %v", r)
			setLastError(errStr)
			retPort = -1
		}
	}()

	stateDir := C.GoString(cStateDir)
	authKey := C.GoString(cAuthKey)
	host := C.GoString(cHost)
	hostPort := int(cHostPort)
	localPort := int(cLocalPort)

	return C.int(pcnetStartInternal(stateDir, authKey, host, hostPort, localPort))
}

//export PcnetStop
func PcnetStop() {
	defer func() {
		if r := recover(); r != nil {
			setLastError(fmt.Sprintf("panic in PcnetStop: %v", r))
		}
	}()
	if err := Stop(); err != nil {
		setLastError(err.Error())
	}
}

//export PcnetRunning
func PcnetRunning() (ret C.int) {
	defer func() {
		if r := recover(); r != nil {
			setLastError(fmt.Sprintf("panic in PcnetRunning: %v", r))
			ret = 0
		}
	}()
	if Running() {
		return 1
	}
	return 0
}

//export PcnetNodeID
func PcnetNodeID() (ret *C.char) {
	defer func() {
		if r := recover(); r != nil {
			setLastError(fmt.Sprintf("panic in PcnetNodeID: %v", r))
			ret = nil
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	id, err := NodeID(ctx)
	if err != nil {
		setLastError(err.Error())
		return nil
	}
	return C.CString(id)
}

//export PcnetLastError
func PcnetLastError() (ret *C.char) {
	defer func() {
		if r := recover(); r != nil {
			ret = nil
		}
	}()
	errStr := LastError()
	if errStr == "" {
		return nil
	}
	return C.CString(errStr)
}

//export PcnetFreeCString
func PcnetFreeCString(s *C.char) {
	defer func() {
		_ = recover()
	}()
	if s != nil {
		C.free(unsafe.Pointer(s))
	}
}

func pcnetDiagnosticsInternal() string {
	defer func() {
		if r := recover(); r != nil {
			setLastError(fmt.Sprintf("panic in PcnetDiagnostics: %v", r))
		}
	}()
	diagJSON, err := GetDiagnosticsJSON()
	if err != nil {
		setLastError(err.Error())
		return ""
	}
	return diagJSON
}

//export PcnetDiagnostics
func PcnetDiagnostics() (ret *C.char) {
	defer func() {
		if r := recover(); r != nil {
			setLastError(fmt.Sprintf("panic in PcnetDiagnostics: %v", r))
			ret = nil
		}
	}()
	str := pcnetDiagnosticsInternal()
	if str == "" {
		return nil
	}
	return C.CString(str)
}

func pcnetPeersInternal() string {
	defer func() {
		if r := recover(); r != nil {
			setLastError(fmt.Sprintf("panic in PcnetPeers: %v", r))
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	peers, err := GetPeers(ctx)
	if err != nil {
		setLastError(err.Error())
		return ""
	}
	b, err := json.Marshal(peers)
	if err != nil {
		setLastError(err.Error())
		return ""
	}
	return asciiJSON(b)
}

// asciiJSON escapes every non-ASCII character of a JSON text as \uXXXX. JNI's NewStringUTF reads
// modified UTF-8, which a device name with an emoji (4-byte UTF-8) would break.
func asciiJSON(b []byte) string {
	var sb strings.Builder
	for _, r := range string(b) {
		if r < 0x80 {
			sb.WriteRune(r)
			continue
		}
		for _, u := range utf16.Encode([]rune{r}) {
			fmt.Fprintf(&sb, "\\u%04x", u)
		}
	}
	return sb.String()
}

// PcnetPeers returns the tailnet's other devices as JSON (see PeersInfo), or NULL on error.
//
//export PcnetPeers
func PcnetPeers() (ret *C.char) {
	defer func() {
		if r := recover(); r != nil {
			setLastError(fmt.Sprintf("panic in PcnetPeers: %v", r))
			ret = nil
		}
	}()
	str := pcnetPeersInternal()
	if str == "" {
		return nil
	}
	return C.CString(str)
}

func pcnetBeginInternal(stateDir, hostname string, mode int, host string, hostPort, localPort int) int {
	cfg := BeginConfig{
		StateDir:  stateDir,
		Hostname:  hostname,
		Mode:      mode,
		Host:      host,
		HostPort:  hostPort,
		LocalPort: localPort,
	}
	if err := Begin(cfg); err != nil {
		setLastError(err.Error())
		return -1
	}
	return 0
}

//export PcnetBegin
func PcnetBegin(cStateDir, cHostname *C.char, cMode C.int, cHost *C.char, cHostPort, cLocalPort C.int) (ret C.int) {
	defer func() {
		if r := recover(); r != nil {
			errStr := fmt.Sprintf("panic in PcnetBegin: %v", r)
			setLastError(errStr)
			ret = -1
		}
	}()

	stateDir := C.GoString(cStateDir)
	hostname := C.GoString(cHostname)
	mode := int(cMode)
	host := C.GoString(cHost)
	hostPort := int(cHostPort)
	localPort := int(cLocalPort)

	return C.int(pcnetBeginInternal(stateDir, hostname, mode, host, hostPort, localPort))
}

//export PcnetState
func PcnetState() (ret *C.char) {
	defer func() {
		if r := recover(); r != nil {
			setLastError(fmt.Sprintf("panic in PcnetState: %v", r))
			ret = C.CString(`{"state":"error","authUrl":"","tailnetIps":[],"localPort":0,"error":"panic in PcnetState"}`)
		}
	}()
	jsonStr := pcnetStateInternal()
	return C.CString(jsonStr)
}
func main() {}
