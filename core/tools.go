//go:build tools

// Pins the packages bound by gomobile so `go mod tidy` keeps them.
package core

import (
	_ "github.com/sagernet/gomobile/bind"
	_ "github.com/sagernet/sing-box/experimental/libbox"
)
