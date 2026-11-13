# Changelog

## v1.7.0

- App 更名 **AnyOpen**，包名改为 `io.github.afeifun.anyopen`（旧的 `dev.fly.hotspotctl` 需卸载重装）
- 新增自适应图标：深色圆底 + 绿色信号弧，含 Android 13 主题图标与单色变体
- 构建脚本改用 `--no-daemon`，避免残留守护进程占用文件锁
- README 增加构建状态徽章与图标

## v1.6.0

控制 App
- 内嵌 MetaCubeXD 面板（WebView），无需另装 GUI
- 全局 / 规则 / 直连 三种模式即时切换并持久化
- 一键开/关热点（root 代点系统开关，带结果校验）
- 每个状态字段附说明；右上角 `?` 打开可折叠的功能说明
- 启动时自动把自身从 `REJECT_ALL` 网络策略里放行

设备端
- 上游网卡自动识别（`Active default network` → `NetworkAgentInfo.InterfaceName`）
- `hs mode` / `hs regen` / `hs probe` 子命令
- `regen.sh` 支持 `PROFILE=` 指定任意订阅文件

## v1.0.0

- 首个可用版本：TPROXY 透明代理、单核心 tun+tproxy、开机自启、5 秒自愈、失败放行
