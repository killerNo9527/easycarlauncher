#!/usr/bin/env bash
# 【历史脚本】虚拟屏内嵌地图已确认需要系统签名并已从代码中删除，此脚本仅作记录。
# 把 CarLauncher 装成系统应用（虚拟屏内嵌地图需要 signature 级权限）
#
# 用法：
#   ./push-privapp.sh /path/to/platform.pk8 /path/to/platform.x509.pem
#   ./push-privapp.sh            # 只推送已在别处签好的 app-platform.apk
#
# 前提：车机/模拟器可 root（adb root 或 su），/system 可 remount 为可写。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK_DEBUG="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
APK_OUT="$ROOT/app/build/outputs/apk/debug/app-platform.apk"
PKG="com.lf.launcher"
DEST="/system/priv-app/CarLauncher"

if [[ $# -eq 2 ]]; then
  echo "==> 用平台密钥重签"
  apksigner sign --key "$1" --cert "$2" --out "$APK_OUT" "$APK_DEBUG"
elif [[ -f "$APK_OUT" ]]; then
  echo "==> 使用已有的 $APK_OUT"
else
  echo "缺少平台签名密钥。虚拟屏内嵌需要的 CAPTURE_VIDEO_OUTPUT / MANAGE_ACTIVITY_STACKS /" >&2
  echo "INJECT_EVENTS 都是 signature 级，用 debug 证书装进去也拿不到。" >&2
  exit 1
fi

echo "==> 以 root 重新挂载 /system"
if adb root >/dev/null 2>&1; then
  adb wait-for-device
  adb remount
else
  adb shell "su -p -c 'mount -o rw,remount /system'"
fi

echo "==> 推送到 $DEST"
adb shell "mkdir -p $DEST"
adb push "$APK_OUT" "$DEST/CarLauncher.apk"
adb shell "chmod 644 $DEST/CarLauncher.apk"

echo "==> 重启生效"
adb reboot
adb wait-for-device
sleep 20

echo "==> 设为默认桌面"
adb shell cmd package set-home-activity $PKG/.LauncherActivity || \
  adb shell "su -p -c 'cmd package set-home-activity $PKG/.LauncherActivity'"

echo "完成。打开 桌面设置 → 地图 → 地图区显示方式 → 虚拟屏内嵌，看「内嵌能力探测」是否变成「可用」。"
