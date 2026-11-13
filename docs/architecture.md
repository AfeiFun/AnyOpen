# 架构

## 组件

| 组件 | 位置 | 作用 |
|---|---|---|
| `mihomo` | `/data/adb/hotspot/mihomo` | 唯一内核：tun 接管手机、tproxy 接管热点 |
| `run.sh` | `/data/adb/hotspot/run.sh` | 守护：拉起核心、装/修规则、跟随上游、失败放行 |
| `hs` | `/data/adb/hotspot/hs` | 命令行：status / probe / on / off / restart / mode / regen / log / test |
| `regen.sh` | `/data/adb/hotspot/regen.sh` | 从订阅生成配置（先 `mihomo -t` 校验再替换） |
| `service.d` | `/data/adb/service.d/99-hotspot-proxy.sh` | 开机拉起 `run.sh` |
| App | `dev.fly.hotspotctl` | 状态展示与控制，全部操作走 root shell |

## 端口

| 端口 | 用途 |
|---|---|
| 7895 | tproxy 入口（TCP+UDP） |
| 1053 | DNS（fake-ip），热点客户端经 nat REDIRECT 进来 |
| 9091 | Clash API + 内嵌面板 `/ui/` |
| 7892 / 7893 | HTTP / SOCKS（本机调试用） |

## 数据路径

```
热点客户端
  → wlan2 收包
  → mangle PREROUTING（第 1 位插入，必须早于 tetherctrl_mangle）
  → 非私有地址打 mark 0x2025
  → ip rule(fwmark) → table 100 → local default dev lo
  → TPROXY 投递给 127.0.0.1:7895
  → mihomo 按订阅规则选择出口
DNS：客户端问网关 :53 → nat PREROUTING REDIRECT 到 :1053
```

手机自身：

```
应用 socket → tun "Meta"（table 2022）→ mihomo 按同一套规则处理
```

## 规则自愈

netd 在网络切换、息屏等事件上会重建 iptables；它可能只清掉其中一部分。守护每 5 秒检查三件事，任一缺失就整体重建：

1. `SB` 链存在
2. `SB` 链里的规则条数 ≥ 10
3. `ip rule` 里有 `fwmark 0x2025`，且 `table 100` 有 `local default`

## 上游跟随

```
dumpsys connectivity → "Active default network: <id>"
                     → NetworkAgentInfo{network{<id>}} 的 InterfaceName
```

拿到网卡名后写进配置的 `interface-name` 并重启核心。这一步是必需的：mihomo 以 root 运行，
如果不绑定物理网卡，它的出站会被别的 VPN tun 抓走，形成「代理套代理」，表现为**直连站点正常、走代理的全部超时**。

## 失败放行

核心不可用时，守护会删掉 `PREROUTING -i <热点口> -j SB` 这条钩子，让客户端回到直连；
核心恢复后下一轮循环自动挂回。避免"代理挂了导致整个热点断网"。
