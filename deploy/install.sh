#!/usr/bin/env bash
# 安装无界面服务器运行浏览器所需的软件；仅支持 Ubuntu 22.04/24.04 x86_64。
# 不启动业务服务、不修改项目配置、不操作数据库。
set -euo pipefail

if [[ "$(uname -s)" != "Linux" || ! -f /etc/os-release ]]; then
    echo "安装失败：仅支持 Ubuntu Linux。" >&2
    exit 1
fi
source /etc/os-release
if [[ "$ID" != "ubuntu" || ! "$VERSION_ID" =~ ^(22\.04|24\.04)$ || "$(uname -m)" != "x86_64" ]]; then
    echo "安装失败：仅支持 Ubuntu 22.04/24.04 x86_64。" >&2
    exit 1
fi
if [[ "$EUID" -ne 0 ]]; then
    echo "请使用 sudo bash Deploy/install.sh 执行安装。" >&2
    exit 1
fi

# JDK 固定使用 17 系列；Xvfb 提供虚拟显示，中文字体避免页面文字缺失。
echo "安装 JDK 17、Xvfb 和浏览器基础依赖……"
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless xvfb xauth fonts-noto-cjk fonts-liberation \
    ca-certificates curl

# Chrome 使用官方 stable 包；由 apt 自动安装其依赖，不手工维护系统库清单。
# 临时目录动态生成，避免覆盖已有文件；退出时仅清理本次下载的软件包。
chrome_download_directory="$(mktemp -d /tmp/geo-chrome-install.XXXXXX)"
trap 'rm -f -- "$chrome_download_directory/google-chrome.deb"; rmdir -- "$chrome_download_directory"' EXIT
chmod 755 "$chrome_download_directory"
echo "安装 Google Chrome stable……"
curl --fail --show-error --location --retry 3 --connect-timeout 30 --max-time 600 \
    https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
    --output "$chrome_download_directory/google-chrome.deb"
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    "$chrome_download_directory/google-chrome.deb"

echo "安装完成。以下为实际安装版本："
/usr/lib/jvm/java-17-openjdk-amd64/bin/java -version
google-chrome --version
command -v xvfb-run
