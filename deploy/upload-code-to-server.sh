#!/bin/sh
# 在本地电脑执行：把当前工作区的源码打包，通过一次 SSH 连接发送到服务器并替换，只需输入一次服务器密码。
# 包含 pom.xml、src、deploy（未提交的修改也会带上）；不包含 .git、target 和 deploy/.env，服务器上的 .env 保持不变。
# 用法：
#   sh deploy/upload-to-server.sh        只上传代码
#   sh deploy/upload-to-server.sh -r     上传后在服务器执行 rebuild.sh 重新构建并启动
set -e

# 服务器登录地址和服务器上仓库根目录（deploy 的上一级），首次使用前填写
SERVER="root@118.145.112.118"
REMOTE_DIR="/root/colorful-geo-mgmt"

case "$SERVER$REMOTE_DIR" in
    *服务器*) echo "请先编辑 deploy/upload-to-server.sh，填写 SERVER 和 REMOTE_DIR" >&2; exit 1 ;;
esac

REBUILD=""
if [ "$1" = "-r" ]; then
    REBUILD="cd deploy && sh rebuild.sh"
fi

cd "$(dirname "$0")/.."

# 关闭 macOS 附加元数据，避免服务器解压出 ._ 文件或打印扩展属性警告
export COPYFILE_DISABLE=1
echo "打包并上传到 $SERVER:$REMOTE_DIR ..."
tar -czf - --no-xattrs --no-mac-metadata --exclude '.DS_Store' --exclude 'deploy/.env' pom.xml src deploy \
    | ssh "$SERVER" "set -e
        cd '$REMOTE_DIR'
        # 1. 先解压到临时目录，上传或解压失败时不影响服务器现有代码
        rm -rf .upload-tmp && mkdir .upload-tmp
        tar -xzf - -C .upload-tmp
        # 2. src 整体替换，保证本地已删除的文件在服务器上也被删除
        rm -rf src && mv .upload-tmp/src src
        mv -f .upload-tmp/pom.xml pom.xml
        # 3. deploy 逐个覆盖，保留服务器自己的 .env
        cp -R .upload-tmp/deploy/. deploy/
        rm -rf .upload-tmp
        echo '代码已更新'
        $REBUILD"
