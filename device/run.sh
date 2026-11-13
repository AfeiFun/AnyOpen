#!/system/bin/sh
# Mi 10S 热点透明代理守护：ap0 → TPROXY → mihomo
DIR=/data/adb/hotspot
BIN=$DIR/mihomo
CFG=${CFG:-$DIR/config.yaml}
TP=7895; DP=1053; MK=0x2025; TB=100
LOG=$DIR/log
PIDF=$DIR/run.pid
mkdir -p "$LOG"

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG/run.log"; }

# 单实例
if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then
  echo "already running: $(cat "$PIDF")"; exit 0
fi
echo $$ > "$PIDF"

up_if() {
  # 找出"活跃默认网络"的接口名：先拿 network id，再匹配同 id 的 NetworkAgentInfo
  id=$(dumpsys connectivity 2>/dev/null | grep -o "Active default network: [0-9]*" | grep -o "[0-9]*" | head -1)
  if [ -n "$id" ]; then
    d=$(dumpsys connectivity 2>/dev/null | grep "NetworkAgentInfo{network{$id}" \
        | grep -o "InterfaceName: [A-Za-z0-9_]*" | head -1 | awk '{print $2}')
    case "$d" in ""|tun*|dummy*|lo|wlan2|ap0|softap0) d="";; esac
    [ -n "$d" ] && { echo "$d"; return; }
  fi
  # 兜底：各 netid 表里带网关的默认路由，排除虚拟口
  ip route show table all 2>/dev/null | awk '$1=="default" && / via /{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}' \
    | grep -vE '^(dummy0|lo|tun[0-9]+|wlan2|ap0|softap0)$' | head -1
}

ap() { for i in ap0 softap0 ap1 wlan1 wlan2; do
        ip link show "$i" >/dev/null 2>&1 && { echo "$i"; return; }; done; }

core_start() {
  pidof mihomo >/dev/null 2>&1 && return
  cd "$DIR" || return
  nohup "$BIN" -d "$DIR" -f "$CFG" >>"$LOG/core.log" 2>&1 &
  log "core started pid=$!"
}

rules_build() {
  ip rule show | grep -q "fwmark $MK/" || ip rule add fwmark $MK/$MK lookup $TB pref 100
  ip route show table $TB 2>/dev/null | grep -q '^local default' || \
    ip route add local default dev lo table $TB
  iptables -t mangle -N SB 2>/dev/null
  iptables -t mangle -F SB
  for n in 0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 \
           169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do
    iptables -t mangle -A SB -d $n -j RETURN
  done
  iptables -t mangle -A SB -p tcp -j TPROXY --on-port $TP --tproxy-mark $MK/$MK
  iptables -t mangle -A SB -p udp -j TPROXY --on-port $TP --tproxy-mark $MK/$MK
  log "mangle chain rebuilt"
}

rules_hook() {  # $1 = 热点接口
  iptables -t mangle -C PREROUTING -i "$1" -j SB 2>/dev/null || \
    iptables -t mangle -I PREROUTING 1 -i "$1" -j SB
  for p in udp tcp; do
    iptables -t nat -C PREROUTING -i "$1" -p $p --dport 53 -j REDIRECT --to-ports $DP 2>/dev/null || \
      iptables -t nat -I PREROUTING 1 -i "$1" -p $p --dport 53 -j REDIRECT --to-ports $DP
  done
}

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 5
core_start
LAST=""
while :; do
  # 出站必须绑物理网卡，否则会被 CMFA 的 tun 抓走形成"代理套代理"
  U=$(up_if)
  if [ -n "$U" ] && ! grep -q "^interface-name: $U$" "$CFG" 2>/dev/null; then
    sed -i "s/^interface-name:.*/interface-name: $U/" "$CFG"
    log "upstream -> $U, restarting core"
    kill "$(pidof mihomo)" 2>/dev/null; sleep 1; core_start
  fi

  # 双隧道保护：CMFA 若自己起 VPN，会和本核心的 tun 打架
  if ip link show tun0 >/dev/null 2>&1; then
    log "CMFA tun0 detected, stopping it (single-core mode)"
    am force-stop com.github.metacubex.clash.meta 2>/dev/null
    sleep 2
  fi

  IF=$(ap)
  if [ -n "$IF" ]; then
    # 三道判据都要查：链存在 ≠ 链有规则 ≠ 有策略路由（netd 会单独清）
    NEED=0
    iptables -t mangle -S SB >/dev/null 2>&1 || NEED=1
    [ "$(iptables -t mangle -S SB 2>/dev/null | wc -l)" -ge 10 ] || NEED=1
    ip rule show | grep -q "fwmark $MK/$MK lookup $TB" || NEED=1
    ip route show table $TB 2>/dev/null | grep -q '^local default' || NEED=1
    [ "$NEED" = 1 ] && rules_build
    rules_hook "$IF"
    # 热点 IPv6 一律关掉：不给客户端发 RA，杜绝 v6 绕过代理
    [ "$(cat /proc/sys/net/ipv6/conf/$IF/disable_ipv6 2>/dev/null)" = 1 ] || \
      echo 1 > /proc/sys/net/ipv6/conf/$IF/disable_ipv6 2>/dev/null
    [ "$LAST" = "$IF" ] || { log "hotspot up on $IF, tproxy attached"; LAST=$IF; }
  else
    [ -z "$LAST" ] || { log "hotspot down"; LAST=""; }
  fi
  if pidof mihomo >/dev/null 2>&1; then
    CORE_UP=1
  else
    log "core died, restarting"; core_start; CORE_UP=0
  fi
  # 核心不可用时摘掉钩子，让客户端退回直连，避免全黑洞
  if [ "$CORE_UP" = 0 ] && [ -n "$IF" ]; then
    iptables -t mangle -D PREROUTING -i "$IF" -j SB 2>/dev/null
  fi
  sleep 5
done
