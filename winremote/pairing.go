package main

// Client side of the PCDash pairing handshake (SRP-6a over TCP :52192 on the home LAN), a port of
// shared/.../pairing/PairingProtocol.java, PairingMessages.java and remote/.../PairingClient.java.
// The wire format and every hash input must stay byte for byte the same as the Java code.

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"strings"
	"time"
	"unicode/utf16"
	"unicode/utf8"
)

const (
	pairingVersion      = "2"
	pairingIdentity     = "pcdash-pair"
	pairingPort         = 52192
	pairingTimeout      = 15 * time.Second
	codeAlphabet        = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
	codeLength          = 6
	maxDeviceNameBytes  = 64
	maxRecordCiphertext = 65536
)

var (
	srpN = mustHexInt("AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73")
	srpG = big.NewInt(2)
	nLen = (srpN.BitLen() + 7) / 8
)

func mustHexInt(s string) *big.Int {
	v, ok := new(big.Int).SetString(s, 16)
	if !ok {
		panic("bad hex constant")
	}
	return v
}

// PairingError says which step failed, for the message the user sees.
type PairingError struct {
	Stage string // connect, hello, proof, server-proof, credentials, ack
	Code  string // the host's error code, when it sent one
	Msg   string
	Err   error
}

func (e *PairingError) Error() string { return e.Msg }
func (e *PairingError) Unwrap() error { return e.Err }

// Credentials is what the host hands over once the code is proven.
type Credentials struct {
	DeviceToken   string `json:"deviceToken"`
	HostTailnetIP string `json:"hostTailnetIp"`
	HostPort      int    `json:"hostPort"`
}

// ---- number and hash helpers (same padding rules as PairingProtocol.java) ----

func pad(v *big.Int) []byte {
	b := v.Bytes()
	if len(b) >= nLen {
		return b[len(b)-nLen:]
	}
	out := make([]byte, nLen)
	copy(out[nLen-len(b):], b)
	return out
}

func hashParts(parts ...[]byte) []byte {
	h := sha256.New()
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}

func normalizeCode(code string) string {
	return strings.ToUpper(strings.TrimSpace(code))
}

func validCode(code string) bool {
	if len(code) != codeLength {
		return false
	}
	for _, r := range code {
		if !strings.ContainsRune(codeAlphabet, r) {
			return false
		}
	}
	return true
}

func computeX(identity, password string, salt []byte) *big.Int {
	inner := hashParts([]byte(identity + ":" + password))
	return new(big.Int).SetBytes(hashParts(salt, inner))
}

func computeK() *big.Int {
	return new(big.Int).SetBytes(hashParts(pad(srpN), pad(srpG)))
}

func computeU(a, b *big.Int) *big.Int {
	return new(big.Int).SetBytes(hashParts(pad(a), pad(b)))
}

func computeClientA(a *big.Int) *big.Int { return new(big.Int).Exp(srpG, a, srpN) }

func computeClientS(serverB, k, x, a, u *big.Int) *big.Int {
	gx := new(big.Int).Exp(srpG, x, srpN)
	base := new(big.Int).Sub(serverB, new(big.Int).Mod(new(big.Int).Mul(k, gx), srpN))
	base.Mod(base, srpN)
	exp := new(big.Int).Add(a, new(big.Int).Mul(u, x))
	return new(big.Int).Exp(base, exp, srpN)
}

func sessionKey(s *big.Int) []byte { return hashParts(pad(s)) }

func computeM1(identity string, salt []byte, a, b *big.Int, k []byte) []byte {
	hN := hashParts(pad(srpN))
	hg := hashParts([]byte{byte(srpG.Int64())})
	xor := make([]byte, len(hN))
	for i := range hN {
		xor[i] = hN[i] ^ hg[i]
	}
	return hashParts(xor, hashParts([]byte(identity)), salt, pad(a), pad(b), k)
}

func computeM2(a *big.Int, m1, k []byte) []byte { return hashParts(pad(a), m1, k) }

func validatePublic(v *big.Int) error {
	if v.Sign() <= 0 || new(big.Int).Mod(v, srpN).Sign() == 0 || v.Cmp(srpN) >= 0 {
		return errors.New("invalid public value")
	}
	return nil
}

func randomExponent() (*big.Int, error) {
	buf := make([]byte, 32)
	for {
		if _, err := rand.Read(buf); err != nil {
			return nil, err
		}
		buf[0] |= 0x80
		e := new(big.Int).SetBytes(buf)
		if e.Cmp(big.NewInt(1)) > 0 && e.Cmp(srpN) < 0 {
			return e, nil
		}
	}
}

func hkdfSHA256(ikm, salt, info []byte, length int) []byte {
	if len(salt) == 0 {
		salt = make([]byte, 32)
	}
	ext := hmac.New(sha256.New, salt)
	ext.Write(ikm)
	prk := ext.Sum(nil)
	out := make([]byte, 0, length)
	var t []byte
	for round := byte(1); len(out) < length; round++ {
		m := hmac.New(sha256.New, prk)
		m.Write(t)
		m.Write(info)
		m.Write([]byte{round})
		t = m.Sum(nil)
		out = append(out, t...)
	}
	return out[:length]
}

func deriveAESKey(sessionK []byte) []byte {
	return hkdfSHA256(sessionK, make([]byte, 32), []byte("pcdash-pair-aes"), 32)
}

// ---- Java DataOutputStream.writeUTF (modified UTF-8) ----

func writeUTF(w io.Writer, s string) error {
	var enc []byte
	for _, u := range utf16.Encode([]rune(s)) {
		switch {
		case u >= 0x0001 && u <= 0x007F:
			enc = append(enc, byte(u))
		case u <= 0x07FF: // includes U+0000, which Java writes as two bytes
			enc = append(enc, byte(0xC0|(u>>6)), byte(0x80|(u&0x3F)))
		default:
			enc = append(enc, byte(0xE0|(u>>12)), byte(0x80|((u>>6)&0x3F)), byte(0x80|(u&0x3F)))
		}
	}
	if len(enc) > 0xFFFF {
		return errors.New("string too long")
	}
	var l [2]byte
	binary.BigEndian.PutUint16(l[:], uint16(len(enc)))
	if _, err := w.Write(l[:]); err != nil {
		return err
	}
	_, err := w.Write(enc)
	return err
}

func readUTF(r io.Reader) (string, error) {
	var l [2]byte
	if _, err := io.ReadFull(r, l[:]); err != nil {
		return "", err
	}
	buf := make([]byte, binary.BigEndian.Uint16(l[:]))
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", err
	}
	var units []uint16
	for i := 0; i < len(buf); {
		c := buf[i]
		switch {
		case c < 0x80:
			units = append(units, uint16(c))
			i++
		case c>>5 == 0x6 && i+1 < len(buf):
			units = append(units, uint16(c&0x1F)<<6|uint16(buf[i+1]&0x3F))
			i += 2
		case c>>4 == 0xE && i+2 < len(buf):
			units = append(units, uint16(c&0x0F)<<12|uint16(buf[i+1]&0x3F)<<6|uint16(buf[i+2]&0x3F))
			i += 3
		default:
			return "", errors.New("malformed UTF")
		}
	}
	return string(utf16.Decode(units)), nil
}

func writeShortBytes(w io.Writer, b []byte) error {
	var l [2]byte
	binary.BigEndian.PutUint16(l[:], uint16(len(b)))
	if _, err := w.Write(l[:]); err != nil {
		return err
	}
	_, err := w.Write(b)
	return err
}

// sanitizeDeviceName mirrors PairingProtocol.sanitizeDeviceName: no control characters, at most 64 bytes
// of UTF-8 cut on a character boundary, "Remote" when nothing is left.
func sanitizeDeviceName(raw string) string {
	var sb strings.Builder
	for _, r := range raw {
		if r >= 0x20 && r != 0x7F {
			sb.WriteRune(r)
		}
	}
	clean := strings.TrimFunc(sb.String(), func(r rune) bool { return r <= 0x20 })
	if clean == "" {
		return "Remote"
	}
	if len(clean) > maxDeviceNameBytes {
		end := maxDeviceNameBytes
		for end > 0 && !utf8.RuneStart(clean[end]) {
			end--
		}
		clean = strings.TrimFunc(clean[:end], func(r rune) bool { return r <= 0x20 })
		if clean == "" {
			return "Remote"
		}
	}
	return clean
}

func computeTranscript(version, deviceName string, a, b, salt []byte) []byte {
	var buf bytesBuffer
	_ = writeUTF(&buf, version)
	_ = writeUTF(&buf, deviceName)
	_ = writeShortBytes(&buf, a)
	_ = writeShortBytes(&buf, b)
	_ = writeShortBytes(&buf, salt)
	return buf.b
}

type bytesBuffer struct{ b []byte }

func (w *bytesBuffer) Write(p []byte) (int, error) { w.b = append(w.b, p...); return len(p), nil }

// ---- encrypted records: byte nonceLen(12) | nonce | int32 len | AES-256-GCM ciphertext+tag ----

func sealRecord(aesKey, aad, plaintext []byte) ([]byte, error) {
	block, err := aes.NewCipher(aesKey)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	ct := gcm.Seal(nil, nonce, plaintext, aad)
	out := []byte{byte(len(nonce))}
	out = append(out, nonce...)
	var l [4]byte
	binary.BigEndian.PutUint32(l[:], uint32(len(ct)))
	out = append(out, l[:]...)
	return append(out, ct...), nil
}

func readRecord(r io.Reader, aesKey, aad []byte) ([]byte, error) {
	var nl [1]byte
	if _, err := io.ReadFull(r, nl[:]); err != nil {
		return nil, err
	}
	if nl[0] != 12 {
		return nil, fmt.Errorf("invalid nonce length: %d", nl[0])
	}
	nonce := make([]byte, 12)
	if _, err := io.ReadFull(r, nonce); err != nil {
		return nil, err
	}
	var cl [4]byte
	if _, err := io.ReadFull(r, cl[:]); err != nil {
		return nil, err
	}
	n := int32(binary.BigEndian.Uint32(cl[:]))
	if n < 0 || n > maxRecordCiphertext {
		return nil, fmt.Errorf("invalid ciphertext length: %d", n)
	}
	ct := make([]byte, n)
	if _, err := io.ReadFull(r, ct); err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(aesKey)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, nonce, ct, aad)
}

// parseCredentials rejects the legacy form that carried a Tailscale auth key.
func parseCredentials(plain []byte) (*Credentials, error) {
	if strings.Contains(string(plain), "\"authKey\"") {
		return nil, errors.New("legacy credentials with authKey are rejected")
	}
	var c Credentials
	if err := json.Unmarshal(plain, &c); err != nil {
		return nil, err
	}
	if c.DeviceToken == "" || c.HostTailnetIP == "" {
		return nil, errors.New("missing deviceToken or hostTailnetIp")
	}
	if c.HostPort <= 0 || c.HostPort > 65535 {
		c.HostPort = 8091
	}
	return &c, nil
}

// hostErrorMessage words the host's error codes.
func hostErrorMessage(code, hostMsg string) string {
	switch code {
	case "ACK_NODE_ID_MISSING":
		return "Pairing failed: the host did not get this device's Tailscale node ID (ACK_NODE_ID_MISSING)."
	case "NODE_NOT_FOUND":
		return "Pairing failed: the host cannot find this device in the tailnet (NODE_NOT_FOUND)."
	case "NODE_TAG_MISMATCH":
		return "Pairing failed: this device has no valid controller tag (tag:pcdash-controller missing)."
	case "API_ERROR":
		return "Pairing failed: the host got an error from the Tailscale API while checking this device (API_ERROR)."
	case "STORE_ERROR":
		return "Pairing failed: the host could not save this device (STORE_ERROR)."
	}
	if code == "" {
		code = "UNKNOWN"
	}
	msg := "Pairing failed on the host: " + code
	if hostMsg != "" {
		msg += " (" + hostMsg + ")"
	}
	return msg
}

const wrongCodeMsg = "The host refused: wrong or expired code. Tap Add device on the host for a new code"

// Pair runs the handshake against the host phone at hostAddr ("192.168.1.20:52192") and returns the credentials.
// nodeID is this device's Tailscale node id, sent to the host in the last step.
func Pair(ctx context.Context, hostAddr, code, deviceName string, nodeID func() (string, error)) (*Credentials, error) {
	d := net.Dialer{Timeout: pairingTimeout}
	conn, err := d.DialContext(ctx, "tcp", hostAddr)
	if err != nil {
		return nil, &PairingError{Stage: "connect", Msg: "Cannot reach the host (check the IP and Wi-Fi): " + err.Error(), Err: err}
	}
	defer conn.Close()
	deadline := time.Now().Add(pairingTimeout)
	_ = conn.SetDeadline(deadline)
	// Close the socket when the caller gives up.
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			conn.Close()
		case <-done:
		}
	}()

	name := sanitizeDeviceName(deviceName)
	a, err := randomExponent()
	if err != nil {
		return nil, &PairingError{Stage: "hello", Msg: err.Error(), Err: err}
	}
	bigA := computeClientA(a)
	if err := validatePublic(bigA); err != nil {
		return nil, &PairingError{Stage: "hello", Msg: err.Error(), Err: err}
	}
	aBytes := pad(bigA)

	// 1. ClientHello, 2. ServerHello
	var hello bytesBuffer
	_ = writeUTF(&hello, pairingVersion)
	_ = writeUTF(&hello, name)
	_ = writeShortBytes(&hello, aBytes)
	if _, err := conn.Write(hello.b); err != nil {
		return nil, &PairingError{Stage: "hello", Msg: "Invalid reply from the host (disconnected during Hello)", Err: err}
	}
	version, err := readUTF(conn)
	if err != nil {
		return nil, &PairingError{Stage: "hello", Msg: "Invalid reply from the host (disconnected or too slow during Hello)", Err: err}
	}
	var sl [1]byte
	if _, err := io.ReadFull(conn, sl[:]); err != nil || sl[0] != 16 {
		return nil, &PairingError{Stage: "hello", Msg: "Invalid reply from the host", Err: err}
	}
	salt := make([]byte, 16)
	if _, err := io.ReadFull(conn, salt); err != nil {
		return nil, &PairingError{Stage: "hello", Msg: "Invalid reply from the host", Err: err}
	}
	var bl [2]byte
	if _, err := io.ReadFull(conn, bl[:]); err != nil {
		return nil, &PairingError{Stage: "hello", Msg: "Invalid reply from the host", Err: err}
	}
	bLenN := int(binary.BigEndian.Uint16(bl[:]))
	if bLenN <= 0 || bLenN > 512 {
		return nil, &PairingError{Stage: "hello", Msg: "Invalid reply from the host"}
	}
	bRaw := make([]byte, bLenN)
	if _, err := io.ReadFull(conn, bRaw); err != nil {
		return nil, &PairingError{Stage: "hello", Msg: "Invalid reply from the host", Err: err}
	}
	if version != pairingVersion {
		return nil, &PairingError{Stage: "hello", Msg: "Invalid reply from the host (protocol version mismatch)"}
	}
	serverB := new(big.Int).SetBytes(bRaw)
	if err := validatePublic(serverB); err != nil {
		return nil, &PairingError{Stage: "hello", Msg: "Invalid reply from the host", Err: err}
	}

	// 3. SRP values
	x := computeX(pairingIdentity, normalizeCode(code), salt)
	u := computeU(bigA, serverB)
	if u.Sign() == 0 {
		return nil, &PairingError{Stage: "hello", Msg: "Invalid reply from the host"}
	}
	s := computeClientS(serverB, computeK(), x, a, u)
	k := sessionKey(s)
	m1 := computeM1(pairingIdentity, salt, bigA, serverB, k)

	// 4. M1, 5. M2
	if _, err := conn.Write(m1); err != nil {
		return nil, &PairingError{Stage: "proof", Msg: wrongCodeMsg, Err: err}
	}
	m2 := make([]byte, 32)
	if _, err := io.ReadFull(conn, m2); err != nil {
		return nil, &PairingError{Stage: "proof", Msg: wrongCodeMsg, Err: err}
	}
	if subtle.ConstantTimeCompare(m2, computeM2(bigA, m1, k)) != 1 {
		return nil, &PairingError{Stage: "server-proof", Msg: "Invalid reply from the host (server proof failed)"}
	}

	// 6. credentials
	transcript := computeTranscript(pairingVersion, name, aBytes, pad(serverB), salt)
	aesKey := deriveAESKey(k)
	plain, err := readRecord(conn, aesKey, transcript)
	if err != nil {
		return nil, &PairingError{Stage: "credentials", Msg: "Invalid reply from the host (could not read the credentials)", Err: err}
	}
	creds, err := parseCredentials(plain)
	if err != nil {
		return nil, &PairingError{Stage: "credentials", Msg: "Invalid reply from the host: " + err.Error(), Err: err}
	}

	// 7. node id, 8. ACK
	id, err := nodeID()
	id = strings.TrimSpace(id)
	if err != nil || id == "" {
		return nil, &PairingError{Stage: "tailnet", Msg: "Tailscale sign-in failed: no Tailscale node ID", Err: err}
	}
	ackJSON, _ := json.Marshal(map[string]string{"nodeId": id})
	ack, err := sealRecord(aesKey, transcript, ackJSON)
	if err != nil {
		return nil, &PairingError{Stage: "ack", Msg: err.Error(), Err: err}
	}
	if _, err := conn.Write(ack); err != nil {
		return nil, &PairingError{Stage: "ack", Msg: "The host disconnected while finishing the handshake (ACK not sent)", Err: err}
	}

	// 9. result
	resPlain, err := readRecord(conn, aesKey, transcript)
	if err != nil {
		return nil, &PairingError{Stage: "ack", Msg: "No result from the host after the ACK: " + err.Error(), Err: err}
	}
	var res struct {
		Status  string `json:"status"`
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal(resPlain, &res); err != nil {
		return nil, &PairingError{Stage: "ack", Msg: "Cannot read the result from the host", Err: err}
	}
	if res.Status != "ok" {
		return nil, &PairingError{Stage: "ack", Code: res.Code, Msg: hostErrorMessage(res.Code, res.Message)}
	}
	return creds, nil
}
