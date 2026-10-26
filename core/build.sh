#!/bin/sh
# Builds app/libs/libbox.aar containing both sing-box (libbox) and the Xray bridge.
# Requires: Go, gomobile/gobind (github.com/sagernet/gomobile v0.1.13), Android SDK + NDK,
# and sources in ../third_party/{sing-box,xray-core}.
set -eu
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
export PATH="$PATH:$(go env GOPATH)/bin"

SING_BOX_VERSION="$(git -C ../third_party/sing-box describe --tags 2>/dev/null | sed 's/^v//')"

# gomobile doesn't create the output directory, and a fresh clone has none (the AAR is git-ignored).
mkdir -p ../app/libs

TAGS="with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0"

gomobile bind -v \
    -o ../app/libs/libbox.aar \
    -target "${TARGETS:-android/arm64,android/amd64}" \
    -androidapi 24 \
    -javapkg=io.nekohasekai \
    -libname=box \
    -trimpath -buildvcs=false \
    -ldflags "-s -w -buildid= -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION:-unknown}" \
    -tags "$TAGS" \
    github.com/sagernet/sing-box/experimental/libbox ./xraybridge
