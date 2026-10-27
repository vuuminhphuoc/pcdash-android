package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/logpolicy"
	"tailscale.com/logtail"
	"tailscale.com/tsnet"
)

func TestStartForwardsTailnetTCPOverTsnet(t *testing.T) {
	_, ts := newTestControlServer(t)

	// Host tsnet server simulating host at :8091
	hostDir := t.TempDir()
	hostServer := &tsnet.Server{
		Dir:        hostDir,
		Hostname:   "pcdash-host",
		ControlURL: ts.URL,
		Ephemeral:  true,
		Logf:       func(string, ...any) {},
		UserLogf:   func(string, ...any) {},
	}
	t.Cleanup(func() {
		hostServer.Close()
	})

	hostLn, err := hostServer.Listen("tcp", ":8091")
	if err != nil {
		t.Fatalf("host Listen failed: %v", err)
	}
	t.Cleanup(func() {
		hostLn.Close()
	})

	// Host echo/response service
	go func() {
		for {
			conn, err := hostLn.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				reader := bufio.NewReader(c)
				line, err := reader.ReadString('\n')
				if err != nil {
					return
				}
				if line == "PING\n" {
					c.Write([]byte("PONG:HOST-8091\n"))
				} else {
					c.Write([]byte("UNKNOWN\n"))
				}
			}(conn)
		}
	}()

	// Wait for host IPs
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	hostStatus, err := hostServer.Up(ctx)
	if err != nil {
		t.Fatalf("hostServer.Up failed: %v", err)
	}
	if hostStatus.Self == nil || len(hostStatus.TailscaleIPs) == 0 {
		t.Fatalf("hostServer has no tailnet IP")
	}
	hostIP := hostStatus.TailscaleIPs[0].String()
	waitForHomeDERPConnected(t, ctx, hostServer)

	// Start remote pcnet forwarder
	remoteDir := t.TempDir()
	cfg := Config{
		StateDir:   remoteDir,
		AuthKey:    "",
		ControlURL: ts.URL,
		Host:       hostIP,
		HostPort:   8091,
		LocalPort:  0,
		Hostname:   "pcdash-remote",
	}

	startCtx, startCancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer startCancel()

	localPort, err := Start(startCtx, cfg)
	if err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	t.Cleanup(func() {
		Stop()
	})

	mu.Lock()
	remoteSrv := activeServer
	mu.Unlock()
	waitForHomeDERPConnected(t, startCtx, remoteSrv)

	if !Running() {
		t.Fatalf("expected Running() to be true")
	}

	nodeIDCtx, nodeIDCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer nodeIDCancel()
	nodeID, err := NodeID(nodeIDCtx)
	if err != nil {
		t.Fatalf("NodeID failed: %v", err)
	}
	if nodeID == "" {
		t.Fatalf("expected non-empty NodeID")
	}

	// Dial local loopback port
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", localPort), 10*time.Second)
	if err != nil {
		t.Fatalf("failed to dial local port %d: %v", localPort, err)
	}
	defer conn.Close()

	if _, err := conn.Write([]byte("PING\n")); err != nil {
		t.Fatalf("write to local port failed: %v", err)
	}

	reply, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		t.Fatalf("read reply failed: %v", err)
	}
	if reply != "PONG:HOST-8091\n" {
		t.Fatalf("unexpected reply: %q", reply)
	}

	// Stop pcnet
	if err := Stop(); err != nil {
		t.Fatalf("Stop failed: %v", err)
	}

	if Running() {
		t.Fatalf("expected Running() to be false after Stop")
	}
}

func TestStopClosesLocalListener(t *testing.T) {
	_, ts := newTestControlServer(t)

	cfg := Config{
		StateDir:   t.TempDir(),
		ControlURL: ts.URL,
		Host:       "127.0.0.1",
		HostPort:   8091,
		LocalPort:  0,
		Hostname:   "pcdash-remote-stop-test",
	}

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	localPort, err := Start(ctx, cfg)
	if err != nil {
		t.Fatalf("Start failed: %v", err)
	}

	// Confirm port is listening
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", localPort), 2*time.Second)
	if err != nil {
		t.Fatalf("expected port %d to be listening: %v", localPort, err)
	}
	conn.Close()

	// Stop
	if err := Stop(); err != nil {
		t.Fatalf("Stop failed: %v", err)
	}

	// Confirm loopback dial now fails
	_, err = net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", localPort), 1*time.Second)
	if err == nil {
		t.Fatalf("expected dial to port %d to fail after Stop", localPort)
	}
}

func TestSecondStartWhileRunningIsRejected(t *testing.T) {
	_, ts := newTestControlServer(t)

	cfg := Config{
		StateDir:   t.TempDir(),
		ControlURL: ts.URL,
		Host:       "127.0.0.1",
		HostPort:   8091,
		LocalPort:  0,
		Hostname:   "pcdash-second-start-reject",
	}

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	localPort, err := Start(ctx, cfg)
	if err != nil {
		t.Fatalf("first Start failed: %v", err)
	}
	defer Stop()

	if !Running() {
		t.Fatalf("expected Running() == true after first Start")
	}

	// Second Start while running must be rejected with a clear error without overwriting
	secondCfg := Config{
		StateDir:   t.TempDir(),
		ControlURL: ts.URL,
		Host:       "127.0.0.1",
		HostPort:   8092,
		LocalPort:  0,
		Hostname:   "pcdash-second-start-reject-2",
	}
	secondPort, secondErr := Start(ctx, secondCfg)
	if secondErr == nil {
		t.Fatalf("expected second Start to be rejected, but succeeded with port %d", secondPort)
	}

	// Verify original instance is still running and listening
	if !Running() {
		t.Fatalf("expected Running() == true after rejected second Start")
	}

	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", localPort), 2*time.Second)
	if err != nil {
		t.Fatalf("expected original port %d to still be listening: %v", localPort, err)
	}
	conn.Close()

	if err := Stop(); err != nil {
		t.Fatalf("Stop failed: %v", err)
	}

	if Running() {
		t.Fatalf("expected Running() == false after Stop")
	}
}

func TestConcurrentStartStopLeavesNothingRunning(t *testing.T) {
	_, ts := newTestControlServer(t)

	baseDir := t.TempDir()
	const workers = 8
	const iterations = 4

	var (
		portsMu       sync.Mutex
		observedPorts = make(map[int]struct{})
		wg            sync.WaitGroup
	)

	recordPort := func(p int) {
		if p <= 0 {
			return
		}
		portsMu.Lock()
		observedPorts[p] = struct{}{}
		portsMu.Unlock()
	}

	for i := range workers {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			for iter := range iterations {
				if (workerID+iter)%2 == 0 {
					cfg := Config{
						StateDir:   fmt.Sprintf("%s/w-%d-%d", baseDir, workerID, iter),
						ControlURL: ts.URL,
						Host:       "127.0.0.1",
						HostPort:   8091,
						LocalPort:  0,
						Hostname:   fmt.Sprintf("pcdash-stress-%d-%d", workerID, iter),
					}
					ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
					port, err := Start(ctx, cfg)
					cancel()
					if err == nil {
						recordPort(port)
					}
				} else {
					_ = Stop()
				}
				time.Sleep(10 * time.Millisecond)
			}
		}(i)
	}

	wg.Wait()

	if err := Stop(); err != nil {
		t.Fatalf("final Stop failed: %v", err)
	}

	if Running() {
		t.Fatalf("expected Running() == false after final Stop")
	}

	portsMu.Lock()
	portsToCheck := make([]int, 0, len(observedPorts))
	for p := range observedPorts {
		portsToCheck = append(portsToCheck, p)
	}
	portsMu.Unlock()

	for _, p := range portsToCheck {
		conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", p), 100*time.Millisecond)
		if err == nil {
			conn.Close()
			t.Fatalf("port %d is still listening after final Stop", p)
		}

		ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p))
		if err != nil {
			t.Fatalf("port %d could not be rebound after final Stop: %v", p, err)
		}
		ln.Close()
	}
}

func TestAndroidStrippedEnvironmentReproduction(t *testing.T) {
	// 1. Strip environment variables to simulate an Android app process
	t.Setenv("HOME", "")
	t.Setenv("USERPROFILE", "")
	t.Setenv("LocalAppData", "")
	t.Setenv("AppData", "")
	t.Setenv("XDG_CACHE_HOME", "")
	t.Setenv("XDG_CONFIG_HOME", "")
	t.Setenv("TS_LOGS_DIR", "")
	t.Setenv("TS_NO_LOGS_NO_SUPPORT", "")

	stateDir := t.TempDir()
	expectedLogsDir := filepath.Join(stateDir, "logs")
	expectedCacheDir := filepath.Join(stateDir, "cache")
	expectedConfigDir := filepath.Join(stateDir, "config")

	_, ts := newTestControlServer(t)

	cfg := Config{
		StateDir:   stateDir,
		ControlURL: ts.URL,
		Host:       "127.0.0.1",
		HostPort:   8091,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	localPort, err := Start(ctx, cfg)
	if err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	defer Stop()

	if localPort <= 0 {
		t.Fatalf("expected positive localPort, got %d", localPort)
	}

	// Verify TS_LOGS_DIR was set and directory was created
	logsDir := os.Getenv("TS_LOGS_DIR")
	if logsDir != expectedLogsDir {
		t.Fatalf("expected TS_LOGS_DIR=%q, got %q", expectedLogsDir, logsDir)
	}
	if fi, err := os.Stat(logsDir); err != nil || !fi.IsDir() {
		t.Fatalf("expected TS_LOGS_DIR to exist as a directory: %v", err)
	}

	// Verify logpolicy.LogsDir returns expectedLogsDir (safe on all platforms)
	actualLogsDir := logpolicy.LogsDir(func(string, ...any) {})
	if actualLogsDir != expectedLogsDir {
		t.Fatalf("expected logpolicy.LogsDir=%q, got %q", expectedLogsDir, actualLogsDir)
	}

	// Verify fallback environment variables
	if os.Getenv("HOME") != stateDir {
		t.Fatalf("expected HOME=%q, got %q", stateDir, os.Getenv("HOME"))
	}
	if os.Getenv("XDG_CACHE_HOME") != expectedCacheDir {
		t.Fatalf("expected XDG_CACHE_HOME=%q, got %q", expectedCacheDir, os.Getenv("XDG_CACHE_HOME"))
	}
	if os.Getenv("XDG_CONFIG_HOME") != expectedConfigDir {
		t.Fatalf("expected XDG_CONFIG_HOME=%q, got %q", expectedConfigDir, os.Getenv("XDG_CONFIG_HOME"))
	}

	// Verify TS_NO_LOGS_NO_SUPPORT is active
	if os.Getenv("TS_NO_LOGS_NO_SUPPORT") != "true" {
		t.Fatalf("expected TS_NO_LOGS_NO_SUPPORT='true', got %q", os.Getenv("TS_NO_LOGS_NO_SUPPORT"))
	}
	if !envknob.NoLogsNoSupport() {
		t.Fatalf("expected envknob.NoLogsNoSupport() == true")
	}
}

func TestNoLogsUpload(t *testing.T) {
	// Test that when TS_NO_LOGS_NO_SUPPORT is set, logtail transport does not upload
	t.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")

	if !envknob.NoLogsNoSupport() {
		t.Fatalf("expected envknob.NoLogsNoSupport() == true")
	}

	tr := logpolicy.NewLogtailTransport(logtail.DefaultHost, nil, nil, func(string, ...any) {})
	req, err := http.NewRequest("POST", "https://"+logtail.DefaultHost+"/c/test/log", strings.NewReader(`{"test":true}`))
	if err != nil {
		t.Fatalf("NewRequest failed: %v", err)
	}
	resp, err := tr.RoundTrip(req)
	if err != nil {
		t.Fatalf("RoundTrip failed: %v", err)
	}
	if resp.Body != nil {
		defer resp.Body.Close()
	}

	// Should succeed locally without actually reaching network
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK from noop transport, got %d", resp.StatusCode)
	}
}

func TestPanicDuringUpDoesNotCrashProcess(t *testing.T) {
	stateDir := t.TempDir()
	_, ts := newTestControlServer(t)

	cfg := Config{
		StateDir:   stateDir,
		ControlURL: ts.URL,
		Host:       "127.0.0.1",
		HostPort:   8091,
	}

	// Inject a panic in testHookBeforeUp to simulate internal tsnet panic
	testHookBeforeUp = func() {
		panic("simulated tsnet panic during start")
	}
	defer func() {
		testHookBeforeUp = nil
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	port, err := Start(ctx, cfg)
	if err == nil {
		Stop()
		t.Fatalf("expected error from panic, got port %d", port)
	}

	if !strings.Contains(err.Error(), "panic during tsnet up") {
		t.Fatalf("expected error to mention panic during tsnet up, got: %v", err)
	}

	lifecycleMu.Lock()
	lastErr := lastError
	curState := state
	running := isRunning
	lifecycleMu.Unlock()

	if !strings.Contains(lastErr, "simulated tsnet panic") {
		t.Fatalf("expected lastError to contain simulated panic, got: %q", lastErr)
	}
	if curState != stateStopped || running {
		t.Fatalf("expected clean state reset (stateStopped, not running), got state=%v running=%v", curState, running)
	}
}

func TestStartRequiresStateDir(t *testing.T) {
	cfg := Config{
		StateDir: "",
		Host:     "127.0.0.1",
		HostPort: 8091,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, err := Start(ctx, cfg)
	if err == nil || err.Error() != "stateDir is required" {
		t.Fatalf("expected 'stateDir is required' error, got %v", err)
	}
}

func TestPcnetExportPanicRecovery(t *testing.T) {
	stateDir := t.TempDir()

	// Test that simulated panic in Start is caught by pcnetStartInternal without crash
	testHookBeforeUp = func() {
		panic("critical unexpected panic during Up")
	}
	defer func() {
		testHookBeforeUp = nil
	}()

	port := pcnetStartInternal(stateDir, "", "127.0.0.1", 8091, 0)
	if port != -1 {
		t.Fatalf("expected pcnetStartInternal to return -1 on panic, got %d", port)
	}

	lifecycleMu.Lock()
	errStr := lastError
	lifecycleMu.Unlock()

	if !strings.Contains(errStr, "critical unexpected panic during Up") {
		t.Fatalf("expected lastError to contain panic message, got: %q", errStr)
	}

	if Running() {
		t.Fatalf("expected Running() == false after panic")
	}
}

func TestLogRingBuffer_SanitizeSecretsAndCapacity(t *testing.T) {
	ring := newLogRingBuffer(200)
	testAuthKey := "tskey-auth-secretkey-abcdef123456"
	ring.setAuthKey(testAuthKey)

	// Feed 250 log entries with various secrets
	for i := range 250 {
		if i == 50 {
			ring.append("Connecting with authkey: " + testAuthKey)
		} else if i == 100 {
			ring.append("Generated privkey:a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90 for node")
		} else if i == 150 {
			ring.append("Using token: secret_token_xyz_987654321")
		} else if i == 220 {
			ring.append("Session authkey=" + testAuthKey + " active")
		} else {
			ring.append(fmt.Sprintf("log line %d: normal operation", i))
		}
	}

	lines := ring.lastLines(300)
	if len(lines) != 200 {
		t.Fatalf("expected capacity capped at 200, got %d", len(lines))
	}

	last40 := ring.lastLines(40)
	if len(last40) != 40 {
		t.Fatalf("expected 40 lines, got %d", len(last40))
	}

	for _, l := range lines {
		if strings.Contains(l, testAuthKey) {
			t.Fatalf("found unredacted authkey in log: %q", l)
		}
		if strings.Contains(l, "secretkey-abcdef123456") {
			t.Fatalf("found authkey payload in log: %q", l)
		}
		if strings.Contains(l, "secret_token_xyz_987654321") {
			t.Fatalf("found secret token in log: %q", l)
		}
		if strings.Contains(l, "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90") {
			t.Fatalf("found private key in log: %q", l)
		}
	}
}

func TestDiagnosticsJSON_NoAuthKeyAndValidFormat(t *testing.T) {
	stateDir := t.TempDir()
	_, ts := newTestControlServer(t)

	testAuthKey := "tskey-auth-supersecretkey999"
	cfg := Config{
		StateDir:   stateDir,
		ControlURL: ts.URL,
		AuthKey:    testAuthKey,
		Host:       "127.0.0.1",
		HostPort:   8091,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	port, err := Start(ctx, cfg)
	if err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	defer Stop()

	if port <= 0 {
		t.Fatalf("expected positive port, got %d", port)
	}

	diagJSON, err := GetDiagnosticsJSON()
	if err != nil {
		t.Fatalf("GetDiagnosticsJSON failed: %v", err)
	}

	if strings.Contains(diagJSON, testAuthKey) {
		t.Fatalf("diagnostics JSON leaked authKey: %s", diagJSON)
	}
	if strings.Contains(diagJSON, "supersecretkey999") {
		t.Fatalf("diagnostics JSON leaked authKey payload: %s", diagJSON)
	}

	var parsed map[string]any
	if err := json.Unmarshal([]byte(diagJSON), &parsed); err != nil {
		t.Fatalf("invalid diagnostics JSON: %v\nJSON: %s", err, diagJSON)
	}

	if _, ok := parsed["backendState"]; !ok {
		t.Fatalf("missing backendState in JSON: %s", diagJSON)
	}
	if _, ok := parsed["interfaces"]; !ok {
		t.Fatalf("missing interfaces in JSON: %s", diagJSON)
	}
	if _, ok := parsed["logs"]; !ok {
		t.Fatalf("missing logs in JSON: %s", diagJSON)
	}

	// Test pcnetDiagnosticsInternal
	internalDiag := pcnetDiagnosticsInternal()
	if internalDiag == "" {
		t.Fatalf("pcnetDiagnosticsInternal returned empty string")
	}
	if strings.Contains(internalDiag, testAuthKey) {
		t.Fatalf("pcnetDiagnosticsInternal leaked authKey: %s", internalDiag)
	}
}

func TestClassifyStartupError_Table(t *testing.T) {
	tests := []struct {
		name         string
		authKey      string
		backendState string
		err          error
		wantRevoked  bool
		wantPrefix   string
	}{
		{
			name:         "running state without authkey is not revoked",
			authKey:      "",
			backendState: "Running",
			err:          nil,
			wantRevoked:  false,
		},
		{
			name:         "transient timeout with Starting state is not revoked",
			authKey:      "",
			backendState: "Starting",
			err:          context.DeadlineExceeded,
			wantRevoked:  false,
		},
		{
			name:         "transient dial error with NoState is not revoked",
			authKey:      "",
			backendState: "NoState",
			err:          errors.New("dial tcp: connect: connection refused"),
			wantRevoked:  false,
		},
		{
			name:         "NeedsLogin state without authkey is revoked",
			authKey:      "",
			backendState: "NeedsLogin",
			err:          context.DeadlineExceeded,
			wantRevoked:  true,
			wantPrefix:   ErrNeedsLoginPrefix,
		},
		{
			name:         "NeedsMachineAuth state without authkey is revoked",
			authKey:      "",
			backendState: "NeedsMachineAuth",
			err:          nil,
			wantRevoked:  true,
			wantPrefix:   ErrNeedsLoginPrefix,
		},
		{
			name:         "control plane invalid authkey error message without authkey is revoked",
			authKey:      "",
			backendState: "Starting",
			err:          errors.New("tsnet.Up: backend: invalid authkey"),
			wantRevoked:  true,
			wantPrefix:   ErrNeedsLoginPrefix,
		},
		{
			name:         "control plane node key expired error message without authkey is revoked",
			authKey:      "",
			backendState: "Starting",
			err:          errors.New("tsnet.Up: backend: node key expired"),
			wantRevoked:  true,
			wantPrefix:   ErrNeedsLoginPrefix,
		},
		{
			name:         "pairing with authkey does not classify as revoked",
			authKey:      "tskey-auth-12345",
			backendState: "NeedsLogin",
			err:          errors.New("invalid authkey"),
			wantRevoked:  false,
		},
		{
			name:         "panic during up does not classify as revoked",
			authKey:      "",
			backendState: "NeedsLogin",
			err:          errors.New("panic during tsnet up: something bad"),
			wantRevoked:  false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			gotErr := ClassifyStartupError(tt.authKey, tt.backendState, tt.err)
			isRevoked := IsNeedsLogin(gotErr)
			if isRevoked != tt.wantRevoked {
				t.Fatalf("IsNeedsLogin = %v, want %v (gotErr: %v)", isRevoked, tt.wantRevoked, gotErr)
			}
			if tt.wantRevoked && !strings.HasPrefix(gotErr.Error(), tt.wantPrefix) {
				t.Fatalf("expected prefix %q, got %q", tt.wantPrefix, gotErr.Error())
			}
		})
	}
}

func TestRevokedDeviceVsTransientNetwork(t *testing.T) {
	tc, ts := newTestControlServer(t)

	// Step 1: Initial startup with fresh stateDir, connect to testcontrol to register and persist state
	remoteDir := t.TempDir()
	cfg := Config{
		StateDir:   remoteDir,
		AuthKey:    "",
		ControlURL: ts.URL,
		Host:       "127.0.0.1",
		HostPort:   8091,
		LocalPort:  0,
		Hostname:   "pcdash-remote-revoke-sim",
	}

	ctx1, cancel1 := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel1()

	port, err := Start(ctx1, cfg)
	if err != nil {
		t.Fatalf("initial Start failed: %v", err)
	}
	if port <= 0 || !Running() {
		Stop()
		t.Fatalf("expected node to be running on initial start")
	}

	// Stop node
	if err := Stop(); err != nil {
		t.Fatalf("Stop failed: %v", err)
	}
	if Running() {
		t.Fatalf("expected Running() == false after Stop")
	}

	// Step 2: Simulate host revoking the node on control plane
	tc.RequireAuthKey = "new-required-authkey-simulating-revoked-node"
	tc.SetExpireAllNodes(true)

	// Step 3: Start again with old stateDir and no authkey => must detect revocation
	ctx2, cancel2 := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel2()

	port2, err2 := Start(ctx2, cfg)
	if err2 == nil {
		Stop()
		t.Fatalf("expected Start to fail for revoked node, but succeeded with port %d", port2)
	}
	if !IsNeedsLogin(err2) {
		t.Fatalf("expected err2 to start with %q, got: %v", ErrNeedsLoginPrefix, err2)
	}

	lastErrStr := LastError()
	if !strings.HasPrefix(lastErrStr, ErrNeedsLoginPrefix) {
		t.Fatalf("expected LastError to start with %q, got: %q", ErrNeedsLoginPrefix, lastErrStr)
	}

	// Step 4: Transient network error (simulating network down / no internet)
	// Point ControlURL to a dead loopback port where TCP connection is refused immediately
	cfgNoNet := Config{
		StateDir:   t.TempDir(),
		AuthKey:    "",
		ControlURL: "http://127.0.0.1:1",
		Host:       "127.0.0.1",
		HostPort:   8091,
		LocalPort:  0,
		Hostname:   "pcdash-remote-nonet-sim",
	}

	ctx3, cancel3 := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel3()

	port3, err3 := Start(ctx3, cfgNoNet)
	if err3 == nil {
		Stop()
		t.Fatalf("expected Start to fail when control URL is unreachable, but got port %d", port3)
	}
	if IsNeedsLogin(err3) {
		t.Fatalf("transient network failure MUST NOT be classified as NEEDS_LOGIN, got: %v", err3)
	}
	if strings.HasPrefix(LastError(), ErrNeedsLoginPrefix) {
		t.Fatalf("LastError MUST NOT start with %q on transient network failure, got: %q", ErrNeedsLoginPrefix, LastError())
	}
}

func TestBeginNonBlocking(t *testing.T) {
	tc, ts := newTestControlServer(t)
	tc.RequireAuth = true

	stateDir := t.TempDir()
	cfg := BeginConfig{
		StateDir:   stateDir,
		Hostname:   "pcdash-remote",
		Mode:       ModeRemoteDial,
		Host:       "100.64.0.1",
		HostPort:   8091,
		LocalPort:  0,
		ControlURL: ts.URL,
	}

	start := time.Now()
	err := Begin(cfg)
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("Begin failed: %v", err)
	}
	t.Cleanup(func() {
		Stop()
	})

	if elapsed > 500*time.Millisecond {
		t.Fatalf("Begin took %v, expected < 500ms", elapsed)
	}
}

func TestStateReportsNeedsLoginWithAuthURL(t *testing.T) {
	tc, ts := newTestControlServer(t)
	tc.RequireAuth = true

	stateDir := t.TempDir()
	cfg := BeginConfig{
		StateDir:   stateDir,
		Hostname:   "pcdash-remote",
		Mode:       ModeRemoteDial,
		Host:       "100.64.0.1",
		HostPort:   8091,
		LocalPort:  0,
		ControlURL: ts.URL,
	}

	if err := Begin(cfg); err != nil {
		t.Fatalf("Begin failed: %v", err)
	}
	t.Cleanup(func() {
		Stop()
	})

	var st StateInfo
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		raw := pcnetStateInternal()
		if err := json.Unmarshal([]byte(raw), &st); err == nil {
			if st.State == "needs_login" {
				break
			}
		}
		time.Sleep(50 * time.Millisecond)
	}

	if st.State != "needs_login" {
		t.Fatalf("expected state 'needs_login', got %q (error: %q)", st.State, st.Error)
	}
	if st.AuthURL == "" || !strings.HasPrefix(st.AuthURL, "http") {
		t.Fatalf("expected valid authUrl, got %q", st.AuthURL)
	}
	if len(st.TailnetIPs) != 0 {
		t.Fatalf("expected empty tailnetIps while in needs_login, got %v", st.TailnetIPs)
	}
}

func TestStateRunningHasTailnetIps(t *testing.T) {
	tc, ts := newTestControlServer(t)
	tc.RequireAuth = true

	stateDir := t.TempDir()
	cfg := BeginConfig{
		StateDir:   stateDir,
		Hostname:   "pcdash-remote",
		Mode:       ModeRemoteDial,
		Host:       "100.64.0.1",
		HostPort:   8091,
		LocalPort:  0,
		ControlURL: ts.URL,
	}

	if err := Begin(cfg); err != nil {
		t.Fatalf("Begin failed: %v", err)
	}
	t.Cleanup(func() {
		Stop()
	})

	var authURL string
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		raw := pcnetStateInternal()
		var st StateInfo
		if err := json.Unmarshal([]byte(raw), &st); err == nil && st.State == "needs_login" && st.AuthURL != "" {
			authURL = st.AuthURL
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if authURL == "" {
		t.Fatalf("never received authUrl")
	}

	if !tc.CompleteAuth(authURL) {
		t.Fatalf("CompleteAuth failed for %q", authURL)
	}

	var runningSt StateInfo
	runDeadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(runDeadline) {
		raw := pcnetStateInternal()
		if err := json.Unmarshal([]byte(raw), &runningSt); err == nil && runningSt.State == "running" {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	if runningSt.State != "running" {
		t.Fatalf("expected state 'running', got %q (error: %q)", runningSt.State, runningSt.Error)
	}
	if runningSt.AuthURL != "" {
		t.Fatalf("expected empty authUrl in running state, got %q", runningSt.AuthURL)
	}
	if len(runningSt.TailnetIPs) == 0 {
		t.Fatalf("expected non-empty tailnetIps in running state")
	}
	if runningSt.LocalPort <= 0 {
		t.Fatalf("expected positive localPort in mode 0, got %d", runningSt.LocalPort)
	}
	if !Running() {
		t.Fatalf("expected Running() == true")
	}
}

func TestHostListenForwardsToLoopback(t *testing.T) {
	_, ts := newTestControlServer(t)

	localLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to listen on loopback: %v", err)
	}
	defer localLn.Close()
	localPort := localLn.Addr().(*net.TCPAddr).Port

	go func() {
		for {
			conn, err := localLn.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				r := bufio.NewReader(c)
				line, err := r.ReadString('\n')
				if err != nil {
					return
				}
				if line == "PING_HOST\n" {
					c.Write([]byte("PONG_LOOPBACK\n"))
				}
			}(conn)
		}
	}()

	hostDir := t.TempDir()
	cfg := BeginConfig{
		StateDir:   hostDir,
		Hostname:   "pcdash-host",
		Mode:       ModeHostListen,
		HostPort:   8091,
		LocalPort:  localPort,
		ControlURL: ts.URL,
	}

	if err := Begin(cfg); err != nil {
		t.Fatalf("Begin failed: %v", err)
	}
	t.Cleanup(func() {
		Stop()
	})

	var hostSt StateInfo
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		raw := pcnetStateInternal()
		if err := json.Unmarshal([]byte(raw), &hostSt); err == nil && hostSt.State == "running" && len(hostSt.TailnetIPs) > 0 {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if hostSt.State != "running" || len(hostSt.TailnetIPs) == 0 {
		t.Fatalf("host did not reach running state, got: %+v", hostSt)
	}
	hostIP := hostSt.TailnetIPs[0]

	clientDir := t.TempDir()
	clientServer := &tsnet.Server{
		Dir:        clientDir,
		Hostname:   "pcdash-remote",
		ControlURL: ts.URL,
		Ephemeral:  true,
		Logf:       func(string, ...any) {},
		UserLogf:   func(string, ...any) {},
	}
	defer clientServer.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	clientStatus, err := clientServer.Up(ctx)
	if err != nil {
		t.Fatalf("clientServer.Up failed: %v", err)
	}
	if clientStatus.Self == nil {
		t.Fatalf("clientStatus has no Self")
	}
	waitForHomeDERPConnected(t, ctx, clientServer)

	target := net.JoinHostPort(hostIP, "8091")
	conn, err := clientServer.Dial(ctx, "tcp", target)
	if err != nil {
		t.Fatalf("failed to dial host on tailnet %s: %v", target, err)
	}
	defer conn.Close()

	if _, err := conn.Write([]byte("PING_HOST\n")); err != nil {
		t.Fatalf("failed to write to tailnet conn: %v", err)
	}

	reply, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		t.Fatalf("failed to read reply from tailnet conn: %v", err)
	}
	if reply != "PONG_LOOPBACK\n" {
		t.Fatalf("unexpected reply: %q", reply)
	}
}

func TestStopResetsState(t *testing.T) {
	_, ts := newTestControlServer(t)

	stateDir := t.TempDir()
	cfg := BeginConfig{
		StateDir:   stateDir,
		Hostname:   "pcdash-remote",
		Mode:       ModeRemoteDial,
		Host:       "100.64.0.1",
		HostPort:   8091,
		LocalPort:  0,
		ControlURL: ts.URL,
	}

	if err := Begin(cfg); err != nil {
		t.Fatalf("Begin failed: %v", err)
	}

	time.Sleep(100 * time.Millisecond)

	if err := Stop(); err != nil {
		t.Fatalf("Stop failed: %v", err)
	}

	raw := pcnetStateInternal()
	var st StateInfo
	if err := json.Unmarshal([]byte(raw), &st); err != nil {
		t.Fatalf("failed to unmarshal state: %v, raw: %s", err, raw)
	}

	if st.State != "stopped" {
		t.Fatalf("expected state 'stopped', got %q", st.State)
	}
	if st.AuthURL != "" {
		t.Fatalf("expected empty authUrl, got %q", st.AuthURL)
	}
	if len(st.TailnetIPs) != 0 {
		t.Fatalf("expected empty tailnetIps, got %v", st.TailnetIPs)
	}
	if st.LocalPort != 0 {
		t.Fatalf("expected localPort 0, got %d", st.LocalPort)
	}
	if Running() {
		t.Fatalf("expected Running() == false")
	}
}

func TestStateJSONNeverContainsAuthKeyOrTokens(t *testing.T) {
	raw := pcnetStateInternal()
	var st StateInfo
	if err := json.Unmarshal([]byte(raw), &st); err != nil {
		t.Fatalf("state JSON failed to parse: %v", err)
	}

	forbidden := []string{"tskey-", "token", "bearer", "secret", "authKey"}
	lowerRaw := strings.ToLower(raw)
	for _, term := range forbidden {
		if strings.Contains(lowerRaw, term) {
			t.Fatalf("state JSON leaked forbidden term %q: %s", term, raw)
		}
	}
}
