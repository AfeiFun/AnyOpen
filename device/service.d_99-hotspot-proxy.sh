#!/system/bin/sh
# Magisk service.d：开机拉起热点透明代理守护
nohup setsid sh /data/adb/hotspot/run.sh </dev/null >/dev/null 2>&1 &
