package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net"
	"os"
	"strings"
	"testing"
	"time"
)

func TestHKDFRFC5869Vector(t *testing.T) {
	ikm, _ := hex.DecodeString("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
	salt, _ := hex.DecodeString("000102030405060708090a0b0c")
	info, _ := hex.DecodeString("f0f1f2f3f4f5f6f7f8f9")
	want := "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
	if got := hex.EncodeToString(hkdfSHA256(ikm, salt, info, 42)); got != want {
		t.Fatalf("OKM = %s, want %s", got, want)
	}
}

func TestWriteReadUTFJavaForm(t *testing.T) {
	for _, s := range []string{"", "2", "Windows PC", "Phước ✓", "emoji \U0001F600", "nul\x00byte"} {
		var b bytesBuffer
		if err := writeUTF(&b, s); err != nil {
			t.Fatal(err)
		}
		got, err := readUTF(bytes.NewReader(b.b))
		if err != nil || got != s {
			t.Fatalf("round trip of %q gave %q, %v", s, got, err)
		}
	}
	// Java writes U+0000 as C0 80 and a supplementary character as two 3-byte surrogates.
	var b bytesBuffer
	_ = writeUTF(&b, "\x00")
	if hex.EncodeToString(b.b) != "0002c080" {
		t.Fatalf("U+0000 = %x", b.b)
	}
	b = bytesBuffer{}
	_ = writeUTF(&b, "\U0001F600")
	if hex.EncodeToString(b.b) != "0006eda0bdedb880" {
		t.Fatalf("U+1F600 = %x", b.b)
	}
}

func TestSanitizeDeviceName(t *testing.T) {
	cases := map[string]string{
		"":                      "Remote",
		"  \t ":                 "Remote",
		"My PC":                 "My PC",
		"a\x01b\x7fc":           "abc",
		"  padded  ":            "padded",
		strings.Repeat("a", 70): strings.Repeat("a", 64),
	}
	for in, want := range cases {
		if got := sanitizeDeviceName(in); got != want {
			t.Errorf("sanitize(%q) = %q, want %q", in, got, want)
		}
	}
	// 3-byte characters are cut on a boundary: 21 of them are 63 bytes, the 22nd would not fit.
	long := strings.Repeat("ế", 30)
	got := sanitizeDeviceName(long)
	if len(got) > 64 || len(got)%3 != 0 || strings.ContainsRune(got, '�') {
		t.Errorf("cut name %q (%d bytes)", got, len(got))
	}
}

func TestNormalizeAndValidCode(t *testing.T) {
	if normalizeCode("  abc234 ") != "ABC234" {
		t.Fatal("normalize")
	}
	for _, c := range []string{"ABC234", "ZZZ999"} {
		if !validCode(c) {
			t.Errorf("%s should be valid", c)
		}
	}
	for _, c := range []string{"", "ABC23", "ABC2345", "ABC0I1", "abc234"} {
		if validCode(c) {
			t.Errorf("%q should be invalid", c)
		}
	}
}

func TestParseCredentials(t *testing.T) {
	c, err := parseCredentials([]byte(`{"deviceToken":"tok","hostTailnetIp":"100.64.0.1","hostPort":8091}`))
	if err != nil || c.DeviceToken != "tok" || c.HostTailnetIP != "100.64.0.1" || c.HostPort != 8091 {
		t.Fatalf("%+v %v", c, err)
	}
	c, err = parseCredentials([]byte(`{"deviceToken":"tok","hostTailnetIp":"100.64.0.1"}`))
	if err != nil || c.HostPort != 8091 {
		t.Fatalf("default port: %+v %v", c, err)
	}
	for _, bad := range []string{`{"authKey":"tskey-x","deviceToken":"a","hostTailnetIp":"b"}`, `{"deviceToken":"a"}`, `not json`} {
		if _, err := parseCredentials([]byte(bad)); err == nil {
			t.Errorf("%s should be rejected", bad)
		}
	}
}

// ---- a host in Go, step for step like PairingManager.processTcpHandshake ----

type fakeHost struct {
	ln       net.Listener
	code     string
	token    string
	tailnet  string
	gotNode  chan string
	gotName  chan string
	result   string // JSON of the final record
	dropAtM1 bool
}

func newFakeHost(t *testing.T, code string) *fakeHost {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	h := &fakeHost{ln: ln, code: code, token: "device-token-1", tailnet: "100.64.0.9", gotNode: make(chan string, 1), gotName: make(chan string, 1), result: `{"status":"ok"}`}
	go h.serve()
	t.Cleanup(func() { ln.Close() })
	return h
}

func (h *fakeHost) addr() string { return h.ln.Addr().String() }

func (h *fakeHost) serve() {
	for {
		c, err := h.ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			_ = c.SetDeadline(time.Now().Add(10 * time.Second))
			h.handshake(c)
		}()
	}
}

func (h *fakeHost) handshake(c net.Conn) {
	version, err := readUTF(c)
	if err != nil || version != pairingVersion {
		return
	}
	name, err := readUTF(c)
	if err != nil {
		return
	}
	var al [2]byte
	if _, err := io.ReadFull(c, al[:]); err != nil {
		return
	}
	aRaw := make([]byte, binary.BigEndian.Uint16(al[:]))
	if _, err := io.ReadFull(c, aRaw); err != nil {
		return
	}
	bigA := new(big.Int).SetBytes(aRaw)
	if validatePublic(bigA) != nil {
		return
	}
	salt := make([]byte, 16)
	_, _ = rand.Read(salt)
	x := computeX(pairingIdentity, h.code, salt)
	v := new(big.Int).Exp(srpG, x, srpN)
	b, _ := randomExponent()
	k := computeK()
	bigB := new(big.Int).Add(new(big.Int).Mul(k, v), new(big.Int).Exp(srpG, b, srpN))
	bigB.Mod(bigB, srpN)

	var hello bytesBuffer
	_ = writeUTF(&hello, pairingVersion)
	hello.b = append(hello.b, 16)
	hello.b = append(hello.b, salt...)
	_ = writeShortBytes(&hello, pad(bigB))
	if _, err := c.Write(hello.b); err != nil {
		return
	}
	u := computeU(bigA, bigB)
	s := new(big.Int).Exp(new(big.Int).Mod(new(big.Int).Mul(bigA, new(big.Int).Exp(v, u, srpN)), srpN), b, srpN)
	key := sessionKey(s)
	wantM1 := computeM1(pairingIdentity, salt, bigA, bigB, key)
	m1 := make([]byte, 32)
	if _, err := io.ReadFull(c, m1); err != nil || h.dropAtM1 || subtle.ConstantTimeCompare(m1, wantM1) != 1 {
		return
	}
	transcript := computeTranscript(pairingVersion, name, pad(bigA), pad(bigB), salt)
	aesKey := deriveAESKey(key)
	credsJSON, _ := json.Marshal(Credentials{DeviceToken: h.token, HostTailnetIP: h.tailnet, HostPort: 8091})
	rec, _ := sealRecord(aesKey, transcript, credsJSON)
	_, _ = c.Write(append(computeM2(bigA, wantM1, key), rec...))
	ackPlain, err := readRecord(c, aesKey, transcript)
	if err != nil {
		return
	}
	var ack struct {
		NodeID string `json:"nodeId"`
	}
	_ = json.Unmarshal(ackPlain, &ack)
	h.gotNode <- ack.NodeID
	h.gotName <- name
	res, _ := sealRecord(aesKey, transcript, []byte(h.result))
	_, _ = c.Write(res)
}

func nodeIDFn(id string) func() (string, error) {
	return func() (string, error) { return id, nil }
}

func TestPairAgainstGoHost(t *testing.T) {
	h := newFakeHost(t, "ABC234")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	creds, err := Pair(ctx, h.addr(), " abc234 ", "Phước's PC", nodeIDFn("12345"))
	if err != nil {
		t.Fatalf("Pair: %v", err)
	}
	if creds.DeviceToken != "device-token-1" || creds.HostTailnetIP != "100.64.0.9" || creds.HostPort != 8091 {
		t.Fatalf("credentials %+v", creds)
	}
	if got := <-h.gotNode; got != "12345" {
		t.Fatalf("host got node id %q", got)
	}
	if got := <-h.gotName; got != "Phước's PC" {
		t.Fatalf("host got name %q", got)
	}
}

func TestPairWrongCode(t *testing.T) {
	h := newFakeHost(t, "ABC234")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	_, err := Pair(ctx, h.addr(), "ZZZ999", "pc", nodeIDFn("1"))
	var pe *PairingError
	if !errors.As(err, &pe) || pe.Stage != "proof" || pe.Msg != wrongCodeMsg {
		t.Fatalf("want a proof error, got %v", err)
	}
}

func TestPairHostError(t *testing.T) {
	h := newFakeHost(t, "ABC234")
	h.result = `{"status":"error","code":"NODE_NOT_FOUND","message":"x"}`
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	_, err := Pair(ctx, h.addr(), "ABC234", "pc", nodeIDFn("1"))
	var pe *PairingError
	if !errors.As(err, &pe) || pe.Code != "NODE_NOT_FOUND" || !strings.Contains(pe.Msg, "NODE_NOT_FOUND") {
		t.Fatalf("want NODE_NOT_FOUND, got %v", err)
	}
}

func TestPairNoNodeID(t *testing.T) {
	h := newFakeHost(t, "ABC234")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	_, err := Pair(ctx, h.addr(), "ABC234", "pc", func() (string, error) { return "", errors.New("none") })
	var pe *PairingError
	if !errors.As(err, &pe) || pe.Stage != "tailnet" {
		t.Fatalf("want a tailnet error, got %v", err)
	}
}

func TestPairUnreachable(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	addr := ln.Addr().String()
	ln.Close()
	_, err := Pair(context.Background(), addr, "ABC234", "pc", nodeIDFn("1"))
	var pe *PairingError
	if !errors.As(err, &pe) || pe.Stage != "connect" {
		t.Fatalf("want a connect error, got %v", err)
	}
}

func TestPairCancelled(t *testing.T) {
	h := newFakeHost(t, "ABC234")
	h.dropAtM1 = true
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err := Pair(ctx, h.addr(), "ABC234", "pc", nodeIDFn("1"))
	if err == nil || time.Since(start) > 5*time.Second {
		t.Fatalf("want a quick failure, got %v after %v", err, time.Since(start))
	}
}

// TestE2EJavaHost pairs with the real Java PairingManager (see the E2E steps in winremote/README.md).
// It only runs when PCDASH_E2E_HOST (host:port), PCDASH_E2E_CODE and PCDASH_E2E_TOKEN_FILE are set.
func TestE2EJavaHost(t *testing.T) {
	addr, code, tokenFile := os.Getenv("PCDASH_E2E_HOST"), os.Getenv("PCDASH_E2E_CODE"), os.Getenv("PCDASH_E2E_TOKEN_FILE")
	if addr == "" || code == "" || tokenFile == "" {
		t.Skip("set PCDASH_E2E_HOST, PCDASH_E2E_CODE and PCDASH_E2E_TOKEN_FILE to run against the Java host")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	creds, err := Pair(ctx, addr, code, "Máy Windows ✓", nodeIDFn("e2e-node-77"))
	if err != nil {
		t.Fatalf("Pair against the Java host: %v", err)
	}
	if creds.HostTailnetIP != "100.64.7.7" || creds.HostPort != 8091 || len(creds.DeviceToken) < 40 {
		t.Fatalf("credentials %+v", creds)
	}
	if err := os.WriteFile(tokenFile, []byte(creds.DeviceToken), 0o600); err != nil {
		t.Fatal(err)
	}
}
