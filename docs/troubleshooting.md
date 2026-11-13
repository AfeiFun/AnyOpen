# 排错手册

这些都是在本项目开发过程中**实测踩到**的问题，按现象归档。

## 1. 客户端能上国内、走代理的全超时

**现象**：日志里 `dial ... error: failed to create session: context deadline exceeded`，国内域名直连正常。

**原因**：mihomo 以 root 运行，出站被另一个 VPN（如 Clash Meta for Android）的 tun 抓走，
于是「连节点」这条连接本身又被代理了一遍，形成嵌套。

**修复**：配置里绑定物理上行 `interface-name: wlan0`（或 `rmnet_dataX`）。
`run.sh` 会自动跟随当前 `Active default network` 改写并重启核心。

## 2. 规则装上了，客户端却完全没被代理

排查顺序：

```sh
su -c 'iptables -t mangle -L SB -n -v'      # 链里有没有规则、计数涨不涨
su -c 'ip rule | grep 0x2025'               # 策略路由在不在
su -c 'ip route show table 100'             # 有没有 local default dev lo
```

常见原因：`SB` 链存在但是空的（netd 只清了规则没删链），而守护只看链是否存在。
本项目已改为「链存在 + 规则数 + 策略路由」三项同查。

## 3. 热点网卡不是 ap0

不同 ROM 上热点接口可能是 `ap0` / `wlan1` / `wlan2` / `softap0`。

```sh
ip -o -4 addr show       # 看哪个网卡拿到了 10.x / 192.168.x 的地址
```

`run.sh` 里 `ap()` 的探测列表可自行增补。

## 4. 钩子挂了但一个包都不进 TPROXY

安卓自带的 `tetherctrl_mangle` 链会 `ACCEPT` 掉转发包，如果钩子插在它后面就永远轮不到。
必须 `iptables -t mangle -I PREROUTING 1 ...` 插到第 1 位。

## 5. 热点自己关了

日志里会出现：

```
SoftApManager[wlan2]: Timeout message scheduled, delay = 600000
```

系统在「无客户端 + 未充电」时 10 分钟自动关闭热点。`settings put global soft_ap_timeout_enabled 0`
在部分 ROM 上无效。可行做法：插着电使用（充电时不会安排该超时）。

## 6. 系统「对已连接的设备使用VPN」开关是坏的

某些自定义 ROM 打开该开关后会**删掉 `iif wlan2 lookup wlan0` 这条转发路由，又不补 VPN 路由**，
转发包落到 `from all unreachable`，客户端表现为「拿得到 IP，但打不开任何网页」。

判断依据：

```sh
ip rule | grep "iif wlan2"     # 打开开关后这条消失
cat /proc/net/dev | grep tun0  # 转发流量 0 字节变化
```

本项目不使用该开关，必须保持关闭。

## 7. 应用连不上本机 127.0.0.1

新装应用可能被 ROM 打上：

```
UID=10xxx policy=262144 (REJECT_ALL)
```

此时该 uid **连回环都连不上**（而 Chrome 正常）。修复：

```sh
su -c 'cmd netpolicy add restrict-background-whitelist <uid>'
```

控制 App 启动时会自动执行这一步。

## 8. WebView 面板报 `ERR_CACHE_MISS`

WebView 缺 `INTERNET` 权限时的典型报错（权限是普通权限，但仍需在 manifest 声明）。

## 9. 每次开机热点网段都变

实测同一台机器两次开机从 `10.145.92.0/24` 变成 `10.176.107.0/24`。
所以规则里**不要写死网段**：本项目按私有地址大类放行，DNS 走网关重定向，换网段无需改动。

## 10. `uiautomator dump` 被 kill

在部分 ROM 上无法用 `uiautomator` 解析控件树（进程直接被 kill）。
「一键开热点」因此改用「打开系统设置页 → 按屏幕比例点击开关 → 校验结果」，
并用 `wm size` 换算坐标；点不中会提示改用「热点设置」手动切换。
