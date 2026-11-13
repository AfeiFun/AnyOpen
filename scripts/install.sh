#!/usr/bin/env bash
# 把内核、脚本、开机自启装到手机（需要 adb + root）
# 用法: ./scripts/install.sh [--apk path/to/app-debug.apk]
set -euo pipefail
cd "$(dirname "$0")/.."

ADB=${ADB:-adb}
DEV=$($ADB devices | awk 'NR==2{print $1}')
[ -n "$DEV" ] || { echo "没有找到设备（adb devices 为空）"; exit 1; }
echo "设备: $DEV"

ARCH=$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')
case "$ARCH" in
  arm64-v8a)   VARIANT=android-arm64-v8 ;;
  armeabi-v7a) VARIANT=android-armv7 ;;
  x86_64)      VARIANT=android-amd64 ;;
  *) echo "不支持的架构: $ARCH"; exit 1 ;;
esac

[ -f build/mihomo ] || ./scripts/fetch-mihomo.sh "$VARIANT"

echo "推送文件…"
$ADB push build/mihomo device/run.sh device/hs device/regen.sh /data/local/tmp/ >/dev/null
$ADB push device/service.d_99-hotspot-proxy.sh /data/local/tmp/ >/dev/null

$ADB shell 'su -c "
  mkdir -p /data/adb/hotspot/log
  mv -f /data/local/tmp/mihomo /data/local/tmp/run.sh /data/local/tmp/hs /data/local/tmp/regen.sh /data/adb/hotspot/
  chmod 755 /data/adb/hotspot/mihomo /data/adb/hotspot/run.sh /data/adb/hotspot/hs /data/adb/hotspot/regen.sh
  mv -f /data/local/tmp/service.d_99-hotspot-proxy.sh /data/adb/service.d/99-hotspot-proxy.sh
  chmod 755 /data/adb/service.d/99-hotspot-proxy.sh
"'

echo "生成配置（从本机 Clash 订阅派生）…"
$ADB shell 'su -c "sh /data/adb/hotspot/regen.sh"' || {
  echo "生成失败：请先在手机上装好 Clash Meta 并导入订阅，"
  echo "或手动把配置放到 /data/adb/hotspot/config.yaml 后重试。"
  exit 1
}

$ADB shell 'su -c "sh /data/adb/hotspot/hs on"'

# 可选：安装控制 App
if [ "${1:-}" = "--apk" ] && [ -n "${2:-}" ]; then
  $ADB install -r "$2"
fi

echo
echo "完成。常用命令："
echo "  su -c 'sh /data/adb/hotspot/hs status'"
echo "  su -c 'sh /data/adb/hotspot/hs probe'"
