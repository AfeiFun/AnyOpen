#!/usr/bin/env bash
# 构建控制 App（需要 JDK 17 + Android SDK）
set -euo pipefail
cd "$(dirname "$0")/../app"

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export JAVA_HOME ANDROID_HOME

echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
echo "产物: app/build/outputs/apk/debug/app-debug.apk"
