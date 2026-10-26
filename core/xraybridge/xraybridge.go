// Package xraybridge exposes a minimal Xray-core API to Android via gomobile.
// It serves protocols and transports that sing-box does not implement (e.g. XHTTP):
// sing-box keeps the TUN device and routing and forwards to a local SOCKS inbound here.
package xraybridge

import (
	"errors"
	"sync"
	"syscall"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/transport/internet"

	// Registers all Xray features and the JSON config loader.
	_ "github.com/xtls/xray-core/main/distro/all"
)

// Protector exempts a socket from the VPN (VpnService.protect on Android).
type Protector interface {
	Protect(fd int32) bool
}

var (
	mu            sync.Mutex
	instance      *core.Instance
	protector     Protector
	registerOnce  sync.Once
	errNotStarted = errors.New("xray is not running")
)

// SetProtector must be called before Start so outbound sockets bypass the tunnel.
func SetProtector(p Protector) {
	mu.Lock()
	protector = p
	mu.Unlock()
	registerOnce.Do(func() {
		_ = internet.RegisterDialerController(func(network, address string, c syscall.RawConn) error {
			mu.Lock()
			p := protector
			mu.Unlock()
			if p == nil {
				return nil
			}
			var protectErr error
			err := c.Control(func(fd uintptr) {
				if !p.Protect(int32(fd)) {
					protectErr = errors.New("failed to protect socket")
				}
			})
			if err != nil {
				return err
			}
			return protectErr
		})
	})
}

// Start launches Xray with a JSON config, replacing any running instance.
func Start(configJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		_ = instance.Close()
		instance = nil
	}
	server, err := core.StartInstance("json", []byte(configJSON))
	if err != nil {
		return err
	}
	instance = server
	return nil
}

// Stop shuts down the running instance, if any.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if instance == nil {
		return errNotStarted
	}
	err := instance.Close()
	instance = nil
	return err
}

// IsRunning reports whether an instance is active.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}

// Version returns the Xray-core version.
func Version() string {
	return core.Version()
}
