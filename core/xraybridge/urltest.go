package xraybridge

import (
	"context"
	"encoding/json"
	"net/netip"
	"strings"
	"sync"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	sJson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"github.com/xtls/xray-core/core"
)

// URLTest measures the delay of each outbound named in tags (comma-separated) with an HTTP
// request to link, through a temporary sing-box instance built from boxConfig. If xrayConfig
// is not empty, a temporary Xray instance is started first for outbounds that forward to it.
// Sockets are protected like the tunnel's own, so results don't depend on whether the VPN is up.
// Returns a JSON object mapping each tag to its delay in milliseconds, or to an error message.
func URLTest(boxConfig, xrayConfig, tags, link string, timeoutMs int32) (string, error) {
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	defer cancel()
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, &testPlatform{})
	options, err := sJson.UnmarshalExtendedContext[option.Options](ctx, []byte(boxConfig))
	if err != nil {
		return "", E.Cause(err, "decode config")
	}

	if xrayConfig != "" {
		xray, err := core.StartInstance("json", []byte(xrayConfig))
		if err != nil {
			return "", E.Cause(err, "start xray")
		}
		defer xray.Close()
	}

	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		return "", E.Cause(err, "create box")
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		return "", E.Cause(err, "start box")
	}

	var (
		wg      sync.WaitGroup
		access  sync.Mutex
		results = map[string]any{}
	)
	timeout := time.Duration(timeoutMs) * time.Millisecond
	for _, tag := range strings.Split(tags, ",") {
		outbound, ok := instance.Outbound().Outbound(tag)
		if !ok {
			results[tag] = "unknown outbound"
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			testCtx, cancel := context.WithTimeout(ctx, timeout)
			defer cancel()
			delay, err := urltest.URLTest(testCtx, link, outbound)
			access.Lock()
			defer access.Unlock()
			if err != nil {
				results[tag] = err.Error()
			} else {
				results[tag] = int32(delay)
			}
		}()
	}
	wg.Wait()
	out, err := json.Marshal(results)
	return string(out), err
}

// testPlatform protects sockets through the registered Protector and turns every other
// platform feature off. The embedded nil interface is never called for disabled features.
type testPlatform struct {
	adapter.PlatformInterface
}

func (p *testPlatform) Initialize(adapter.NetworkManager) error { return nil }

func (p *testPlatform) UsePlatformAutoDetectInterfaceControl() bool { return true }

func (p *testPlatform) AutoDetectInterfaceControl(fd int) error {
	mu.Lock()
	pr := protector
	mu.Unlock()
	if pr != nil && !pr.Protect(int32(fd)) {
		return E.New("failed to protect socket")
	}
	return nil
}

func (p *testPlatform) UsePlatformInterface() bool               { return false }
func (p *testPlatform) UsePlatformDefaultInterfaceMonitor() bool { return true }
func (p *testPlatform) UsePlatformNetworkInterfaces() bool       { return false }
func (p *testPlatform) UnderNetworkExtension() bool              { return false }
func (p *testPlatform) NetworkExtensionIncludeAllNetworks() bool { return false }
func (p *testPlatform) ClearDNSCache()                           {}
func (p *testPlatform) UsePlatformWIFIMonitor() bool             { return false }
func (p *testPlatform) UsePlatformConnectionOwnerFinder() bool   { return false }
func (p *testPlatform) UsePlatformNotification() bool            { return false }
func (p *testPlatform) UsePlatformNeighborResolver() bool        { return false }
func (p *testPlatform) UsePlatformShell() bool                   { return false }
func (p *testPlatform) UsePlatformBridge() bool                  { return false }
func (p *testPlatform) MyInterfaceAddress() []netip.Addr         { return nil }
func (p *testPlatform) CreateDefaultInterfaceMonitor(logger.Logger) tun.DefaultInterfaceMonitor {
	return &idleMonitor{}
}

// idleMonitor reports no default interface; dialing relies on socket protection instead.
type idleMonitor struct{}

func (m *idleMonitor) Start() error                         { return nil }
func (m *idleMonitor) Close() error                         { return nil }
func (m *idleMonitor) DefaultInterface() *control.Interface { return nil }
func (m *idleMonitor) OverrideAndroidVPN() bool             { return false }
func (m *idleMonitor) AndroidVPNEnabled() bool              { return false }
func (m *idleMonitor) RegisterCallback(tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	return nil
}
func (m *idleMonitor) UnregisterCallback(*list.Element[tun.DefaultInterfaceUpdateCallback]) {}
func (m *idleMonitor) RegisterMyInterface(string)                                           {}
func (m *idleMonitor) MyInterfaces() []string                                               { return nil }
