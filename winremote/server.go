package main

// The page and its small local API. Everything here listens on 127.0.0.1 only. A website open in the same
// browser must not be able to drive the PC through it, so:
//   - the Host header has to be the loopback address (blocks DNS rebinding),
//   - every request except the page itself carries X-PCDash-Local, a secret generated at each start and
//     placed in the page, which another origin cannot read.

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

//go:embed ui/index.html
var indexHTML string

const (
	localHeader   = "X-PCDash-Local"
	proxyTimeout  = 25 * time.Second
	maxLocalBody  = 4096
	defaultHostPt = 8091
)

// tailnetAPI is what the server needs from the embedded node (a fake in tests).
type tailnetAPI interface {
	State() TailnetState
	NodeID() (string, error)
	HTTPClient() *http.Client
	Start() error
	Logout() error
}

type App struct {
	cfg      *ConfigStore
	tn       tailnetAPI
	version  string
	secret   string
	port     int
	hostname string

	// pairAddr turns the host's LAN address into the pairing endpoint; tests point it elsewhere.
	pairAddr func(ip string) string
	// hostBase is where the remote API of the host is reached; tests point it at a local server.
	hostBase func(c Config) string
}

func NewApp(cfg *ConfigStore, tn tailnetAPI, version string, port int) *App {
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	return &App{
		cfg: cfg, tn: tn, version: version, port: port,
		secret: hex.EncodeToString(b),
		pairAddr: func(ip string) string {
			return net.JoinHostPort(ip, strconv.Itoa(pairingPort))
		},
		hostBase: func(c Config) string {
			p := c.HostPort
			if p <= 0 {
				p = defaultHostPt
			}
			return "http://" + net.JoinHostPort(c.HostTailnetIP, strconv.Itoa(p))
		},
	}
}

func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", a.handleIndex)
	mux.HandleFunc("/local/ping", a.guard(false, a.handlePing))
	mux.HandleFunc("/local/state", a.guard(true, a.handleState))
	mux.HandleFunc("/local/pair", a.guard(true, a.handlePair))
	mux.HandleFunc("/local/unpair", a.guard(true, a.handleUnpair))
	mux.HandleFunc("/local/signout", a.guard(true, a.handleSignOut))
	mux.HandleFunc("/local/language", a.guard(true, a.handleLanguage))
	mux.HandleFunc("/v1/", a.guard(true, a.handleProxy))
	return mux
}

func (a *App) hostOK(r *http.Request) bool {
	h := r.Host
	if i := strings.LastIndex(h, ":"); i >= 0 {
		if p, err := strconv.Atoi(h[i+1:]); err != nil || (a.port != 0 && p != a.port) {
			return false
		}
		h = h[:i]
	}
	return h == "127.0.0.1" || h == "localhost" || h == "[::1]"
}

// guard checks the Host header and, when needSecret is set, the per-run secret.
func (a *App) guard(needSecret bool, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.hostOK(r) {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "forbidden"})
			return
		}
		if needSecret {
			got := r.Header.Get(localHeader)
			if subtle.ConstantTimeCompare([]byte(got), []byte(a.secret)) != 1 {
				writeJSON(w, http.StatusForbidden, map[string]any{"error": "forbidden"})
				return
			}
		}
		next(w, r)
	}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func (a *App) handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	if !a.hostOK(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	page := strings.NewReplacer("{{SECRET}}", a.secret, "{{VERSION}}", a.version).Replace(indexHTML)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'")
	_, _ = io.WriteString(w, page)
}

func (a *App) handlePing(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"app": "pcdash-remote-win", "version": a.version})
}

func (a *App) handleState(w http.ResponseWriter, r *http.Request) {
	c := a.cfg.Get()
	writeJSON(w, http.StatusOK, map[string]any{
		"version":     a.version,
		"paired":      c.Paired(),
		"deviceName":  c.DeviceName,
		"defaultName": a.hostname,
		"hostIp":      c.HostTailnetIP,
		"pcId":        c.PcID,
		"language":    c.Language,
		"tailnet":     a.tn.State(),
	})
}

var privateIPv4 = regexp.MustCompile(`^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$`)

// isPrivateIPv4 is the same rule as PairingQr.isPrivateIpv4: pairing only happens on the home LAN.
func isPrivateIPv4(ip string) bool {
	m := privateIPv4.FindStringSubmatch(ip)
	if m == nil {
		return false
	}
	var b [4]int
	for i := 0; i < 4; i++ {
		b[i], _ = strconv.Atoi(m[i+1])
		if b[i] > 255 {
			return false
		}
	}
	return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168)
}

func readBody(r *http.Request, v any) error {
	return json.NewDecoder(io.LimitReader(r.Body, maxLocalBody)).Decode(v)
}

func (a *App) handlePair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	var in struct {
		IP   string `json:"ip"`
		Code string `json:"code"`
		Name string `json:"name"`
	}
	if err := readBody(r, &in); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "Bad request"})
		return
	}
	in.IP = strings.TrimSpace(in.IP)
	code := normalizeCode(in.Code)
	if !isPrivateIPv4(in.IP) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "Enter the home phone's Wi-Fi address (like 192.168.1.20)"})
		return
	}
	if !validCode(code) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "The pairing code has 6 letters and digits"})
		return
	}
	if a.tn.State().State != "running" {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": "Sign in to Tailscale first"})
		return
	}
	name := in.Name
	if strings.TrimSpace(name) == "" {
		name = a.hostname
	}
	ctx, cancel := context.WithTimeout(r.Context(), 40*time.Second)
	defer cancel()
	creds, err := Pair(ctx, a.pairAddr(in.IP), code, name, a.tn.NodeID)
	if err != nil {
		var pe *PairingError
		stage := ""
		if errors.As(err, &pe) {
			stage = pe.Stage
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": false, "error": err.Error(), "stage": stage})
		return
	}
	cur := a.cfg.Get()
	if err := a.cfg.Set(Config{
		DeviceToken: creds.DeviceToken, HostTailnetIP: creds.HostTailnetIP, HostPort: creds.HostPort,
		DeviceName: sanitizeDeviceName(name), Language: cur.Language,
	}); err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"ok": false, "error": "Could not save the pairing: " + err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *App) handleUnpair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	cur := a.cfg.Get()
	if err := a.cfg.Clear(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if cur.Language != "" {
		_ = a.cfg.Set(Config{Language: cur.Language})
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *App) handleSignOut(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	if err := a.tn.Logout(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	_ = a.cfg.Clear()
	_ = a.tn.Start()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *App) handleLanguage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	var in struct {
		Language string `json:"language"`
	}
	if err := readBody(r, &in); err != nil || (in.Language != "" && in.Language != "en" && in.Language != "vi") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false})
		return
	}
	c := a.cfg.Get()
	c.Language = in.Language
	if err := a.cfg.Set(c); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

var (
	actionRe = regexp.MustCompile(`^(wake|sleep|hibernate|shutdown|restart|lock|logoff|abort)$`)
	pcIDRe   = regexp.MustCompile(`^[A-Za-z0-9_-]{1,24}$`)
)

// proxyRoute says whether a request may go to the host, and with which query parameters. The update download
// is left out: it is for the Android app.
func proxyRoute(method, path string) (allowed []string, ok bool) {
	switch {
	case method == http.MethodGet && (path == "/v1/pcs"):
		return nil, true
	case method == http.MethodGet && (path == "/v1/status" || path == "/v1/metrics"):
		return []string{"pc"}, true
	case method == http.MethodGet && path == "/v1/quota":
		return []string{"pc", "refresh"}, true
	case method == http.MethodGet && path == "/v1/history":
		return []string{"pc", "minutes"}, true
	case method == http.MethodGet && path == "/v1/log":
		return []string{"limit"}, true
	case method == http.MethodPost && strings.HasPrefix(path, "/v1/") && actionRe.MatchString(path[4:]):
		return []string{"pc"}, true
	}
	return nil, false
}

func cleanQuery(in url.Values, allowed []string) string {
	out := url.Values{}
	for _, k := range allowed {
		v := in.Get(k)
		if v == "" {
			continue
		}
		switch k {
		case "pc":
			if !pcIDRe.MatchString(v) {
				continue
			}
		case "refresh":
			if v != "1" && v != "true" {
				continue
			}
		case "minutes", "limit":
			if n, err := strconv.Atoi(v); err != nil || n <= 0 || n > 1000 {
				continue
			}
		}
		out.Set(k, v)
	}
	return out.Encode()
}

func (a *App) handleProxy(w http.ResponseWriter, r *http.Request) {
	allowed, ok := proxyRoute(r.Method, r.URL.Path)
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found"})
		return
	}
	c := a.cfg.Get()
	if !c.Paired() {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "not_paired"})
		return
	}
	target := a.hostBase(c) + r.URL.Path
	if q := cleanQuery(r.URL.Query(), allowed); q != "" {
		target += "?" + q
	}
	ctx, cancel := context.WithTimeout(r.Context(), proxyTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, r.Method, target, nil)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"error": "bad_request"})
		return
	}
	req.Header.Set("Authorization", "Bearer "+c.DeviceToken)
	if l := r.Header.Get("Accept-Language"); l != "" {
		req.Header.Set("Accept-Language", l)
	}
	if r.Method == http.MethodPost {
		req.ContentLength = 0
	}
	resp, err := a.tn.HTTPClient().Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"error": "unreachable", "detail": err.Error()})
		return
	}
	defer resp.Body.Close()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, io.LimitReader(resp.Body, 1<<20))
}
