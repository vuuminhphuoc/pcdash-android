package main

// PCDash Remote for Windows: the same remote control as the Android app, for a Windows PC. It signs in to
// Tailscale through its own embedded node (no Tailscale app, no VPN), pairs with the home phone like the
// Android remote does, and shows the control, monitor, quota and log screens in the browser at
// http://127.0.0.1:<port>/. A notification-area icon opens the page and quits the app.

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"strings"
	"syscall"
	"time"
)

// Set by -ldflags "-X main.version=...".
var version = "dev"

const defaultPort = 18092

func dataDir() string {
	if d := os.Getenv("PCDASH_REMOTE_DIR"); d != "" {
		return d
	}
	base := os.Getenv("APPDATA")
	if base == "" {
		base, _ = os.UserConfigDir()
	}
	return filepath.Join(base, "PCDashRemote")
}

var nonHost = regexp.MustCompile(`[^A-Za-z0-9-]+`)

// tailnetHostname is the name this PC gets in the tailnet.
func tailnetHostname() string {
	h, _ := os.Hostname()
	h = strings.Trim(nonHost.ReplaceAllString(h, "-"), "-")
	if h == "" {
		h = "pc"
	}
	if len(h) > 40 {
		h = h[:40]
	}
	return "pcdash-win-" + strings.ToLower(h)
}

func hostLabel() string {
	h, _ := os.Hostname()
	h = strings.TrimSpace(h)
	if h == "" {
		return "Windows PC"
	}
	return h + " (Windows)"
}

func openBrowser(url string) {
	_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
}

// isOurs asks whatever listens on the port whether it is another copy of this app.
func isOurs(port int) bool {
	c := &http.Client{Timeout: 1500 * time.Millisecond}
	resp, err := c.Get(fmt.Sprintf("http://127.0.0.1:%d/local/ping", port))
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	return strings.Contains(string(b), "pcdash-remote-win")
}

func fail(msg string) {
	fmt.Fprintln(os.Stderr, msg)
	fatalBox(msg)
	os.Exit(1)
}

func main() {
	port := flag.Int("port", defaultPort, "local port of the page (0 picks a free one)")
	noBrowser := flag.Bool("no-browser", false, "do not open the browser at start")
	noTray := flag.Bool("no-tray", false, "no notification-area icon; stop with Ctrl+C")
	showVersion := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *showVersion {
		fmt.Println("PCDash Remote for Windows", version)
		return
	}

	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", *port))
	if err != nil {
		// Another copy is already running: just show its page.
		if *port != 0 && isOurs(*port) {
			if !*noBrowser {
				openBrowser(fmt.Sprintf("http://127.0.0.1:%d/", *port))
			}
			return
		}
		ln, err = net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			fail("PCDash Remote cannot listen on the loopback address: " + err.Error())
		}
	}
	actual := ln.Addr().(*net.TCPAddr).Port

	dir := dataDir()
	cfg := NewConfigStore(dir)
	if err := cfg.Load(); err != nil {
		fail("PCDash Remote cannot read the saved pairing (" + filepath.Join(dir, "config.json") + "): " + err.Error())
	}
	tn := NewTailnet(filepath.Join(dir, "tailnet"), tailnetHostname())
	app := NewApp(cfg, tn, version, actual)
	app.hostname = hostLabel()
	if err := tn.Start(); err != nil {
		fmt.Fprintln(os.Stderr, "tailnet:", err)
	}

	srv := &http.Server{Handler: app.Handler(), ReadHeaderTimeout: 10 * time.Second}
	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			fail("PCDash Remote server stopped: " + err.Error())
		}
	}()
	url := fmt.Sprintf("http://127.0.0.1:%d/", actual)
	fmt.Println("PCDash Remote", version, "at", url)
	if !*noBrowser {
		openBrowser(url)
	}

	shutdown := func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
		tn.Stop()
	}
	if *noTray {
		stop := make(chan os.Signal, 1)
		signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
		<-stop
		shutdown()
		return
	}
	runTray("PCDash Remote "+version, func() { openBrowser(url) }, shutdown)
}
