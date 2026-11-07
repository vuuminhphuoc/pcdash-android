package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
)

// Config is what pairing leaves on this PC. The device token lets whoever holds it control the PC, so the file
// is private to the user (0600) and lives in %APPDATA%.
type Config struct {
	DeviceToken   string `json:"deviceToken"`
	HostTailnetIP string `json:"hostTailnetIp"`
	HostPort      int    `json:"hostPort"`
	DeviceName    string `json:"deviceName"`
	PcID          string `json:"pcId,omitempty"`
	Language      string `json:"language,omitempty"`
}

func (c Config) Paired() bool {
	return c.DeviceToken != "" && c.HostTailnetIP != ""
}

type ConfigStore struct {
	path string
	mu   sync.Mutex
	cfg  Config
}

func NewConfigStore(dir string) *ConfigStore {
	return &ConfigStore{path: filepath.Join(dir, "config.json")}
}

func (s *ConfigStore) Load() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	data, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		s.cfg = Config{}
		return nil
	}
	if err != nil {
		return err
	}
	var c Config
	if err := json.Unmarshal(data, &c); err != nil {
		return err
	}
	s.cfg = c
	return nil
}

func (s *ConfigStore) Get() Config {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.cfg
}

func (s *ConfigStore) Set(c Config) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, s.path); err != nil {
		return err
	}
	s.cfg = c
	return nil
}

func (s *ConfigStore) Clear() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cfg = Config{}
	err := os.Remove(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}
