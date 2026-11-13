#!/usr/bin/env bash
# 下载 mihomo 内核（默认 android arm64）
# 用法: ./scripts/fetch-mihomo.sh [android-arm64-v8|android-armv7|android-amd64]
set -euo pipefail
cd "$(dirname "$0")/.."

VARIANT=${1:-android-arm64-v8}
TAG=$(curl -sIL -o /dev/null -w '%{url_effective}' https://github.com/MetaCubeX/mihomo/releases/latest | sed 's#.*/tag/##')
[ -n "$TAG" ] || { echo "无法获取 mihomo 最新版本号"; exit 1; }

URL="https://github.com/MetaCubeX/mihomo/releases/download/${TAG}/mihomo-${VARIANT}-${TAG}.gz"
mkdir -p build
echo "下载 $TAG ($VARIANT)"
curl -fsSL -o build/mihomo.gz "$URL"
gunzip -f build/mihomo.gz
chmod +x build/mihomo
echo "已就绪: build/mihomo  ($(du -h build/mihomo | cut -f1))"
