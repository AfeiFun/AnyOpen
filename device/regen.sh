#!/system/bin/sh
# 从 CMFA 当前订阅生成 root 侧 mihomo 配置（不需要 Mac / python）
D=/data/adb/hotspot
OUT=$D/config.yaml
TMP=$D/config.new.yaml
# 订阅来源：优先用 PROFILE 环境变量指定；否则取 Clash Meta for Android 里最新的一份
if [ -z "$PROFILE" ]; then
  PROFILE=$(ls -t /data/data/com.github.metacubex.clash.meta/files/imported/*/config.yaml 2>/dev/null | head -1)
fi
[ -n "$PROFILE" ] && [ -f "$PROFILE" ] || {
  echo "ERR: 找不到订阅配置。请用: PROFILE=/path/to/config.yaml sh regen.sh"; exit 1; }
SRC=$PROFILE

UP=""
id=$(dumpsys connectivity 2>/dev/null | grep -o "Active default network: [0-9]*" | grep -o "[0-9]*" | head -1)
if [ -n "$id" ]; then
  UP=$(dumpsys connectivity 2>/dev/null | grep "NetworkAgentInfo{network{$id}" \
       | grep -o "InterfaceName: [A-Za-z0-9_]*" | head -1 | awk '{print $2}')
  case "$UP" in tun*|dummy*|lo|wlan2|ap0|softap0) UP="";; esac
fi
[ -n "$UP" ] || UP=$(ip route show table all 2>/dev/null \
  | awk '$1=="default" && / via /{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}' \
  | grep -vE '^(dummy0|lo|tun[0-9]+|wlan2|ap0|softap0)$' | head -1)
[ -n "$UP" ] || UP=wlan0

FM=0
grep -q '^  enhanced-mode:' "$SRC" || FM=1

awk -v up="$UP" -v fm="$FM" '
  /^port:/                 { print "port: 7892"; next }
  /^socks-port:/           { print "socks-port: 7893"; next }
  /^redir-port:/           { next }
  /^tproxy-port:/          { next }
  /^allow-lan:/            { print "allow-lan: false"; next }
  /^external-controller:/  { print "external-controller: 127.0.0.1:9091"; next }
  /^external-ui:/          { next }
  /^dns:/                  { print; if (fm == 1) print "  enhanced-mode: fake-ip"; next }
  /^  listen:/             { print "  listen: 0.0.0.0:1053"; next }
  { gsub(/127\.0\.0\.1:7874/, "127.0.0.1:1053"); print }
  END {
    print "external-ui: /data/adb/hotspot/ui"
    print "interface-name: " up
    print "listeners:"
    print "  - name: tproxy-in"
    print "    type: tproxy"
    print "    port: 7895"
    print "    listen: 0.0.0.0"
    print "    udp: true"
    print "tun:"
    print "  enable: true"
    print "  stack: mixed"
    print "  auto-route: true"
    print "  auto-detect-interface: true"
    print "  dns-hijack:"
    print "    - any:53"
    print "  mtu: 9000"
  }' "$SRC" > "$TMP" || { echo "ERR: 生成失败"; rm -f "$TMP"; exit 1; }

if "$D/mihomo" -t -d "$D" -f "$TMP" >/dev/null 2>&1; then
  mv -f "$TMP" "$OUT"
  echo "OK: 已更新 $OUT (上游 $UP)"
else
  echo "ERR: 新配置校验失败，保留原配置"
  "$D/mihomo" -t -d "$D" -f "$TMP" 2>&1 | tail -3
  rm -f "$TMP"
  exit 1
fi
