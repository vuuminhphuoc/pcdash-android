package main

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/signal"
	"strings"
	"testing"
	"time"
)

// TestManualUI serves the page against a fake host with realistic data, so the screens can be looked at in a
// browser. It only runs with PCDASH_UI_MANUAL=<port> and blocks until interrupted:
//
//	PCDASH_UI_MANUAL=18199 go test -run TestManualUI -timeout 0 ./...
//
// PCDASH_UI_STATE=setup shows the sign-in and pairing steps instead of the paired screens.
func TestManualUI(t *testing.T) {
	portStr := os.Getenv("PCDASH_UI_MANUAL")
	if portStr == "" {
		t.Skip("manual only")
	}
	now := time.Now().UnixMilli()
	host := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Header.Get("Authorization") != "Bearer manual-token":
			w.WriteHeader(401)
			io.WriteString(w, `{"error":"unauthorized"}`)
		case r.URL.Path == "/v1/pcs":
			io.WriteString(w, `{"pcs":[{"id":"pc1","name":"Desktop","online":true,"port":3389,"rtt_ms":3,"configured":true},{"id":"pc2","name":"Laptop","online":false,"port":null,"rtt_ms":0,"configured":true}]}`)
		case r.URL.Path == "/v1/status":
			if r.URL.Query().Get("pc") == "pc2" {
				io.WriteString(w, `{"online":false,"port":null,"rtt_ms":0,"configured":true}`)
			} else {
				io.WriteString(w, `{"online":true,"port":3389,"rtt_ms":3,"configured":true}`)
			}
		case r.URL.Path == "/v1/metrics":
			io.WriteString(w, `{"ts":1,"pc":{"online":true,"cpu":{"percent":37.5,"tempC":58,"name":"Ryzen 7 5800X"},"ram":{"usedMB":18200,"totalMB":32768,"percent":55.5},"gpu":{"percent":81,"tempC":71,"name":"RTX 3060","memUsedMB":9000,"memTotalMB":12288},"net":{"downBps":3145728,"upBps":51200},"disks":[{"name":"C:","usedGB":412,"totalGB":931},{"name":"D:","usedGB":1700,"totalGB":1863}],"uptimeSec":273000,"sys":{"tempC":42}},"phone":{"battery":{"percent":88,"charging":true,"tempC":33},"ram":{"availMB":1800,"totalMB":6000},"storage":{"freeGB":40,"totalGB":128},"wifi":{"rssiDbm":-52,"linkMbps":433},"tailscale":true,"thermal":"none","uptimeSec":99999}}`)
		case r.URL.Path == "/v1/quota":
			fmt.Fprintf(w, `{"fetchedAt":%d,"stale":false,"loading":false,"providers":[{"id":"codex","name":"Codex","groups":[{"name":"","windows":[{"id":"5h","label":"5 giờ","pooledRemainingPct":64,"accountsWithQuota":3,"accountsTotal":4,"resetsAt":%d,"accounts":[]},{"id":"wk","label":"Tuần","pooledRemainingPct":12,"accountsWithQuota":1,"accountsTotal":4,"resetsAt":%d,"accounts":[]}]}]}]}`, now-120000, now+3*3600*1000, now+4*86400*1000)
		case r.URL.Path == "/v1/log":
			fmt.Fprintf(w, `{"entries":[{"ts":%d,"device":"Pixel","source":"remote","action":"sleep · Desktop","ok":true},{"ts":%d,"device":"web","source":"web","action":"wake","ok":false}]}`, now-60000, now-3600000)
		case r.Method == http.MethodPost:
			fmt.Fprintf(w, `{"ok":true,"action":"%s","detail":"sent"}`, strings.TrimPrefix(r.URL.Path, "/v1/"))
		default:
			w.WriteHeader(404)
			io.WriteString(w, `{"error":"not_found"}`)
		}
	}))
	defer host.Close()

	state := TailnetState{State: "running", IPs: []string{"100.64.0.5"}}
	if os.Getenv("PCDASH_UI_STATE") == "setup" {
		state = TailnetState{State: "needs_login", AuthURL: "https://login.tailscale.com/a/example"}
	}
	tn := &fakeTailnet{st: state, node: "7"}
	cfg := NewConfigStore(t.TempDir())
	if os.Getenv("PCDASH_UI_STATE") != "setup" {
		_ = cfg.Set(Config{DeviceToken: "manual-token", HostTailnetIP: "100.64.0.9", HostPort: 8091, DeviceName: "Desk"})
	}
	var port int
	fmt.Sscanf(portStr, "%d", &port)
	app := NewApp(cfg, tn, "manual", port)
	app.hostname = "Manual PC"
	app.hostBase = func(Config) string { return host.URL }
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(ln, app.Handler())
	t.Logf("UI at http://127.0.0.1:%d/", port)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt)
	<-sig
}
