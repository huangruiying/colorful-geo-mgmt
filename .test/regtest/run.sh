#!/usr/bin/env bash
# ============================================================================
#  平台发布真实回归 · 一键运行脚本
#  位置: .test/regtest/run.sh
#  作用: 编译主工程(可选) → 生成依赖 classpath → 编译回归脚本 → 真实浏览器跑回归
#  用法:
#    .test/regtest/run.sh                # 跑全部已走通平台（东方财富、简书、小红书）
#    .test/regtest/run.sh JIANSHU       # 只跑简书（单平台快速验证）
#    .test/regtest/run.sh XIAOHONGSHU   # 只跑小红书
#    SKIP_MVN_COMPILE=1 .test/regtest/run.sh   # 跳过 mvn 编译，用已有 target/classes
#  依赖:
#    - IntelliJ 自带 Maven（默认路径，可用 MVN 环境变量覆盖）
#    - 本地 MySQL 凭据（GEO_DB_*，见 .env.example；从 .test/regtest/.env 或环境变量读取）
#    - Playwright 浏览器已下载到 PLAYWRIGHT_BROWSERS_PATH（默认 /tmp/pw-browsers）
# ============================================================================
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$ROOT" || exit 1

# ---- 环境 / 工具链 ----
MVN="${MVN:-/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn}"
# 锁定项目 JDK 17（与 pom 的 java.version 一致），避免系统 JDK 错位
export JAVA_HOME="${JAVA_HOME:-/Users/huangry/Library/Java/JavaVirtualMachines/ms-17.0.17/Contents/Home}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-/tmp/pw-browsers}"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
# 发布自动化强制直连，避免本机 AI 工具本地代理劫持页面请求
unset HTTP_PROXY http_proxy HTTPS_PROXY https_proxy 2>/dev/null || true

# ---- 本地凭据（不入库）----
if [ -f "$SCRIPT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$SCRIPT_DIR/.env"
  set +a
fi

# ---- 1) 编译主工程（确保测的是"当前"代码）----
if [ "${SKIP_MVN_COMPILE:-0}" != "1" ]; then
  echo "[regtest] mvn -o compile process-resources ..."
  "$MVN" -o compile process-resources -q \
    || echo "[regtest][warn] mvn compile 失败，将使用已有 target/classes（如需重编请先在 IntelliJ 构建）"
fi

# ---- 2) 生成依赖 classpath（缓存到 .cp，离线用 maven-dependency-plugin:3.7.0）----
if [ "${SKIP_CP:-0}" != "1" ] || [ ! -f "$SCRIPT_DIR/.cp" ]; then
  echo "[regtest] 生成 classpath ..."
  "$MVN" -o org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
    -Dmdep.outputFile="$SCRIPT_DIR/.cp" -q \
    || { echo "[regtest][error] 生成 classpath 失败（离线 Maven 可能缺插件，请先 IntelliJ 联网拉取一次）"; exit 2; }
fi
CP="target/classes:$(cat "$SCRIPT_DIR/.cp")"

# ---- 3) 编译回归脚本 ----
echo "[regtest] 编译 .test/regtest ..."
rm -rf "$SCRIPT_DIR/out"
mkdir -p "$SCRIPT_DIR/out"
javac -encoding UTF-8 -cp "$CP" -d "$SCRIPT_DIR/out" "$SCRIPT_DIR"/*.java \
  || { echo "[regtest][error] 编译回归脚本失败（见上方 javac 报错）"; exit 3; }

# ---- 4) 运行 ----
echo "[regtest] 运行回归 ..."
"$JAVA" -cp "$SCRIPT_DIR/out:$CP" org.huangry.colorful.geo.regtest.RegressionRunner "$@"
code=$?
exit $code
