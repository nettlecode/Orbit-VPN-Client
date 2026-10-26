package xraybridge

import (
	"encoding/json"
	"os"
	"testing"
)

// Needs a Shadowsocks server: ORBIT_TEST_SS=127.0.0.1:18388 (aes-256-gcm, password orbit-test-pw).
func TestURLTest(t *testing.T) {
	server := os.Getenv("ORBIT_TEST_SS")
	if server == "" {
		t.Skip("ORBIT_TEST_SS not set")
	}
	host, port := server[:len(server)-6], server[len(server)-5:]
	config := `{
		"log": {"disabled": true},
		"dns": {"servers": [{"type": "hosts", "tag": "hosts", "predefined": {"ss.test": ["` + host + `"]}}]},
		"route": {"default_domain_resolver": "hosts", "auto_detect_interface": true},
		"outbounds": [
			{"type": "shadowsocks", "tag": "ok", "server": "ss.test", "server_port": ` + port + `, "method": "aes-256-gcm", "password": "orbit-test-pw"},
			{"type": "shadowsocks", "tag": "bad", "server": "ss.test", "server_port": ` + port + `, "method": "aes-256-gcm", "password": "wrong"}
		]
	}`
	out, err := URLTest(config, "", "ok,bad,missing", "https://www.gstatic.com/generate_204", 5000)
	if err != nil {
		t.Fatal(err)
	}
	var results map[string]any
	if err := json.Unmarshal([]byte(out), &results); err != nil {
		t.Fatal(err)
	}
	t.Log(out)
	delay, _ := results["ok"].(float64)
	_, badFailed := results["bad"].(string)
	_, missingFailed := results["missing"].(string)
	if delay <= 0 || !badFailed || !missingFailed {
		t.Fatalf("unexpected results: %s", out)
	}
}
