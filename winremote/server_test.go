package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

type fakeTailnet struct {
	mu       sync.Mutex
	st       TailnetState
	node     string
	logouts  int
	starts   int
	hostSrv  *httptest.Server
	lastAuth string
	lastURL  string
}

func (f *fakeTailnet) State() TailnetState      { f.mu.Lock(); defer f.mu.Unlock(); return f.st }
func (f *fakeTailnet) NodeID() (string, error)  { return f.node, nil }
func (f *fakeTailnet) HTTPClient() *http.Client { return http.DefaultClient }
func (f *fakeTailnet) Start() error             { f.mu.Lock(); f.starts++; f.mu.Unlock(); return nil }
func (f *fakeTailnet) Logout() error            { f.mu.Lock(); f.logouts++; f.mu.Unlock(); return nil }

type env struct {
	app  *App
	srv  *httptest.Server
	tn   *fakeTailnet
	host *httptest.Server
	cfg  *ConfigStore
}

func newEnv(t *testing.T) *env {
	t.Helper()
	e := &env{tn: &fakeTailnet{st: TailnetState{State: "running"}, node: "42"}}
	e.cfg = NewConfigStore(t.TempDir())
	e.host = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		e.tn.mu.Lock()
		e.tn.lastAuth = r.Header.Get("Authorization")
		e.tn.lastURL = r.URL.RequestURI()
		e.tn.mu.Unlock()
		if r.Header.Get("Authorization") != "Bearer secret-token" {
			w.WriteHeader(401)
			_, _ = io.WriteString(w, `{"error":"unauthorized"}`)
			return
		}
		switch r.URL.Path {
		case "/v1/status":
			_, _ = io.WriteString(w, `{"online":true,"port":3389,"rtt_ms":4}`)
		case "/v1/sleep":
			_, _ = io.WriteString(w, `{"ok":true,"action":"sleep","detail":"sent"}`)
		default:
			w.WriteHeader(404)
			_, _ = io.WriteString(w, `{"error":"not_found"}`)
		}
	}))
	e.app = NewApp(e.cfg, e.tn, "test", 0)
	e.app.hostBase = func(Config) string { return e.host.URL }
	e.srv = httptest.NewServer(e.app.Handler())
	// the app checks the port in the Host header; learn the real one.
	e.app.port = portOf(e.srv.URL)
	t.Cleanup(func() { e.srv.Close(); e.host.Close() })
	return e
}

func portOf(u string) int {
	i := strings.LastIndex(u, ":")
	n := 0
	for _, c := range u[i+1:] {
		n = n*10 + int(c-'0')
	}
	return n
}

func (e *env) do(t *testing.T, method, path, body string, hdr map[string]string) (int, string) {
	t.Helper()
	req, _ := http.NewRequest(method, e.srv.URL+path, strings.NewReader(body))
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	if v, ok := hdr["Host"]; ok {
		req.Host = v
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

func (e *env) auth() map[string]string { return map[string]string{localHeader: e.app.secret} }

func (e *env) pairUp(t *testing.T) {
	t.Helper()
	if err := e.cfg.Set(Config{DeviceToken: "secret-token", HostTailnetIP: "100.64.0.9", HostPort: 8091, DeviceName: "PC"}); err != nil {
		t.Fatal(err)
	}
}

func TestIndexServesSecretAndRejectsForeignHost(t *testing.T) {
	e := newEnv(t)
	code, body := e.do(t, "GET", "/", "", nil)
	if code != 200 || !strings.Contains(body, e.app.secret) || strings.Contains(body, "{{SECRET}}") {
		t.Fatalf("index: %d, secret in page: %v", code, strings.Contains(body, e.app.secret))
	}
	code, _ = e.do(t, "GET", "/", "", map[string]string{"Host": "evil.example.com"})
	if code != 403 {
		t.Fatalf("foreign Host got %d", code)
	}
	code, _ = e.do(t, "GET", "/", "", map[string]string{"Host": "127.0.0.1:1"})
	if code != 403 {
		t.Fatalf("wrong port got %d", code)
	}
}

func TestLocalAPINeedsSecret(t *testing.T) {
	e := newEnv(t)
	for _, p := range []string{"/local/state", "/v1/status", "/v1/pcs"} {
		if code, _ := e.do(t, "GET", p, "", nil); code != 403 {
			t.Errorf("%s without the secret: %d", p, code)
		}
		if code, _ := e.do(t, "GET", p, "", map[string]string{localHeader: "wrong"}); code != 403 {
			t.Errorf("%s with a wrong secret: %d", p, code)
		}
	}
	if code, _ := e.do(t, "POST", "/local/unpair", "{}", nil); code != 403 {
		t.Errorf("unpair without the secret: %d", code)
	}
	if code, _ := e.do(t, "GET", "/local/ping", "", nil); code != 200 {
		t.Errorf("ping: %d", code)
	}
}

func TestStateReflectsPairing(t *testing.T) {
	e := newEnv(t)
	_, body := e.do(t, "GET", "/local/state", "", e.auth())
	var s struct {
		Paired  bool         `json:"paired"`
		Tailnet TailnetState `json:"tailnet"`
	}
	_ = json.Unmarshal([]byte(body), &s)
	if s.Paired || s.Tailnet.State != "running" {
		t.Fatalf("before pairing: %s", body)
	}
	e.pairUp(t)
	_, body = e.do(t, "GET", "/local/state", "", e.auth())
	_ = json.Unmarshal([]byte(body), &s)
	if !s.Paired {
		t.Fatalf("after pairing: %s", body)
	}
	if strings.Contains(body, "secret-token") {
		t.Fatal("the device token must never reach the page")
	}
}

func TestProxyAddsBearerAndFiltersQuery(t *testing.T) {
	e := newEnv(t)
	e.pairUp(t)
	code, body := e.do(t, "GET", "/v1/status?pc=pc1&evil=1&x=%0d%0a", "", e.auth())
	if code != 200 || !strings.Contains(body, `"online":true`) {
		t.Fatalf("status: %d %s", code, body)
	}
	if e.tn.lastAuth != "Bearer secret-token" {
		t.Fatalf("host saw Authorization %q", e.tn.lastAuth)
	}
	if e.tn.lastURL != "/v1/status?pc=pc1" {
		t.Fatalf("host saw %q", e.tn.lastURL)
	}
	code, body = e.do(t, "POST", "/v1/sleep", "", e.auth())
	if code != 200 || !strings.Contains(body, `"action":"sleep"`) {
		t.Fatalf("sleep: %d %s", code, body)
	}
	// a bad pc id is dropped, not forwarded
	e.do(t, "GET", "/v1/status?pc=../etc", "", e.auth())
	if e.tn.lastURL != "/v1/status" {
		t.Fatalf("bad pc forwarded: %q", e.tn.lastURL)
	}
}

func TestProxyRefusesWhatTheRemoteNeverSends(t *testing.T) {
	e := newEnv(t)
	e.pairUp(t)
	cases := []struct{ method, path string }{
		{"GET", "/v1/update/apk"}, {"GET", "/v1/update/info"}, {"POST", "/v1/status"}, {"GET", "/v1/sleep"},
		{"POST", "/v1/format"}, {"DELETE", "/v1/status"}, {"POST", "/v1/"}, {"GET", "/v1/status/extra"}, {"POST", "/v1/sleep/now"},
	}
	for _, c := range cases {
		code, _ := e.do(t, c.method, c.path, "", e.auth())
		if code == 200 {
			t.Errorf("%s %s was forwarded", c.method, c.path)
		}
	}
}

func TestProxyNeedsPairing(t *testing.T) {
	e := newEnv(t)
	if code, body := e.do(t, "GET", "/v1/status", "", e.auth()); code != 409 || !strings.Contains(body, "not_paired") {
		t.Fatalf("unpaired proxy: %d %s", code, body)
	}
}

func TestProxyPasses401And502(t *testing.T) {
	e := newEnv(t)
	e.cfg.Set(Config{DeviceToken: "revoked", HostTailnetIP: "100.64.0.9", HostPort: 8091})
	if code, _ := e.do(t, "GET", "/v1/status", "", e.auth()); code != 401 {
		t.Fatalf("revoked token: %d", code)
	}
	e.host.Close()
	e.pairUp(t)
	if code, _ := e.do(t, "GET", "/v1/status", "", e.auth()); code != 502 {
		t.Fatalf("host down: %d", code)
	}
}

func TestPairValidation(t *testing.T) {
	e := newEnv(t)
	bad := []string{
		`{"ip":"8.8.8.8","code":"ABC234"}`,
		`{"ip":"192.168.1.20","code":"abc"}`,
		`{"ip":"192.168.1.20","code":"ABC0I1"}`,
		`{"ip":"192.168.1.999","code":"ABC234"}`,
		`{"ip":"100.64.0.1","code":"ABC234"}`,
		`not json`,
	}
	for _, b := range bad {
		if code, _ := e.do(t, "POST", "/local/pair", b, e.auth()); code != 400 {
			t.Errorf("%s got %d", b, code)
		}
	}
	e.tn.st = TailnetState{State: "needs_login"}
	if code, _ := e.do(t, "POST", "/local/pair", `{"ip":"192.168.1.20","code":"ABC234"}`, e.auth()); code != 409 {
		t.Errorf("not signed in got %d", code)
	}
}

func TestPairStoresCredentials(t *testing.T) {
	e := newEnv(t)
	h := newFakeHost(t, "ABC234")
	e.app.pairAddr = func(string) string { return h.addr() }
	code, body := e.do(t, "POST", "/local/pair", `{"ip":"192.168.1.20","code":"abc234","name":"Desk PC"}`, e.auth())
	if code != 200 || !strings.Contains(body, `"ok":true`) {
		t.Fatalf("pair: %d %s", code, body)
	}
	c := e.cfg.Get()
	if !c.Paired() || c.DeviceToken != "device-token-1" || c.HostTailnetIP != "100.64.0.9" || c.DeviceName != "Desk PC" {
		t.Fatalf("saved %+v", c)
	}
	if got := <-h.gotNode; got != "42" {
		t.Fatalf("node id %q", got)
	}
	// wrong code leaves nothing behind
	e2 := newEnv(t)
	e2.app.pairAddr = func(string) string { return h.addr() }
	_, body = e2.do(t, "POST", "/local/pair", `{"ip":"192.168.1.20","code":"ZZZ999"}`, e2.auth())
	if !strings.Contains(body, `"ok":false`) || e2.cfg.Get().Paired() {
		t.Fatalf("wrong code: %s paired=%v", body, e2.cfg.Get().Paired())
	}
}

func TestUnpairKeepsLanguage(t *testing.T) {
	e := newEnv(t)
	e.pairUp(t)
	e.do(t, "POST", "/local/language", `{"language":"vi"}`, e.auth())
	e.do(t, "POST", "/local/unpair", `{}`, e.auth())
	c := e.cfg.Get()
	if c.Paired() || c.Language != "vi" {
		t.Fatalf("after unpair %+v", c)
	}
	if code, _ := e.do(t, "POST", "/local/language", `{"language":"xx"}`, e.auth()); code != 400 {
		t.Fatalf("bad language got %d", code)
	}
}

func TestSignOutForgetsEverything(t *testing.T) {
	e := newEnv(t)
	e.pairUp(t)
	e.do(t, "POST", "/local/signout", `{}`, e.auth())
	if e.cfg.Get().Paired() || e.tn.logouts != 1 || e.tn.starts != 1 {
		t.Fatalf("paired=%v logouts=%d starts=%d", e.cfg.Get().Paired(), e.tn.logouts, e.tn.starts)
	}
}

func TestConfigFileIsPrivateAndSurvivesReload(t *testing.T) {
	dir := t.TempDir()
	s := NewConfigStore(dir)
	if err := s.Set(Config{DeviceToken: "t", HostTailnetIP: "100.64.0.1", HostPort: 8091, DeviceName: "x"}); err != nil {
		t.Fatal(err)
	}
	s2 := NewConfigStore(dir)
	if err := s2.Load(); err != nil || !s2.Get().Paired() {
		t.Fatalf("reload: %v %+v", err, s2.Get())
	}
	if err := s2.Clear(); err != nil || s2.Get().Paired() {
		t.Fatalf("clear: %v", err)
	}
	if err := s2.Clear(); err != nil {
		t.Fatalf("clearing twice: %v", err)
	}
}

func TestIsPrivateIPv4(t *testing.T) {
	yes := []string{"192.168.1.20", "10.0.0.1", "172.16.5.5", "172.31.255.255"}
	no := []string{"", "8.8.8.8", "100.64.0.1", "127.0.0.1", "172.32.0.1", "192.168.1", "192.168.1.256", "a.b.c.d", "192.168.1.1.1", "::1"}
	for _, ip := range yes {
		if !isPrivateIPv4(ip) {
			t.Errorf("%s should be private", ip)
		}
	}
	for _, ip := range no {
		if isPrivateIPv4(ip) {
			t.Errorf("%q should not be private", ip)
		}
	}
}

func TestTailnetHostname(t *testing.T) {
	if got := tailnetHostname(); !strings.HasPrefix(got, "pcdash-win-") || strings.ContainsAny(got, " _.") {
		t.Fatalf("hostname %q", got)
	}
}
