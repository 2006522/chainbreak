#!/usr/bin/env bash
# ChainBreak 构建脚本（无需 Gradle / Loom）
# 原理：26.x 的 Minecraft jar 已不再混淆，直接对官方 jar 编译即可；
#      mixin 配置使用官方类名、不加 refmap。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK:-C:/Users/wnw/AppData/Roaming/.minecraft/runtime/java-runtime-epsilon/bin}"
DEPS="${DEPS:-C:/Users/wnw/AppData/Local/Temp}"
LIB="$DEPS/mclibs"
FAPI="$DEPS/fapi"
CLIENT="$DEPS/client2645.jar"
MIXIN="$DEPS/mixin-0.8.7.jar"
LOADER_STUB="$DEPS/loaderstub/fabric-loader-stub.jar"

CP="$CLIENT;$MIXIN;$FAPI/*;$LOADER_STUB;$LIB/*"

echo "== 清理 =="
rm -rf "$ROOT/build/classes"
mkdir -p "$ROOT/build/classes" "$ROOT/dist"

echo "== 编译 =="
"$JDK/javac.exe" -encoding UTF-8 -nowarn -d "$ROOT/build/classes" -cp "$CP" \
  $(find "$ROOT/src/main/java" -name "*.java")

echo "== 打包 =="
VER=$(grep -o '"version": "[^"]*"' "$ROOT/src/main/resources/fabric.mod.json" | head -1 | sed 's/.*: "//;s/"//')
OUT="$ROOT/dist/chainbreak-${VER}+mc26.4.jar"
rm -f "$OUT"
cd "$ROOT/build/classes" && "$JDK/jar.exe" cf "$OUT" .
cd "$ROOT/src/main/resources" && "$JDK/jar.exe" uf "$OUT" .
echo "完成: $OUT"
