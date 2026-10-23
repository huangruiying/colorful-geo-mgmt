# Linux 一键部署

服务器需安装 Docker Engine 和 Compose v2，上传整个项目（不能只上传 deploy）。宿主机无需安装 Java、Maven 或浏览器；构建需要访问镜像仓库、Maven Central、Ubuntu 软件源和 Playwright 下载源。

## 首次启动

编辑本目录 `.env`，填写不同的 MySQL root 密码、应用数据库密码及后台密码 BCrypt 哈希。`.env` 不提交 Git；从 Git 获取项目时，先复制 `.env.example` 为 `.env`。

用 `htpasswd -nBC 10 admin` 交互生成密码哈希，取输出中 `admin:` 后的完整内容。Ubuntu 的 htpasswd 由 apache2-utils 提供。密码和哈希使用单引号包住，保留哈希中的 `$`。

```bash
DOCKER_DIR=/你的项目绝对路径/deploy
docker compose -f "$DOCKER_DIR/docker-compose.yaml" --env-file "$DOCKER_DIR/.env" up -d
```

首次自动构建 Java 17 镜像、安装对应 Playwright Chromium、启动 MySQL 8.4 并执行 `001_init.sql`，数据库就绪后启动应用。访问 `http://服务器IP:8010/admin/login.html`，使用 `.env` 中账号登录。模型渠道和平台登录态仍需在后台配置；部署成功不代表真实平台发布已验证。

MySQL 不映射宿主机端口。应用默认监听所有网卡；由宿主机 Nginx 代理时，将 `GEO_BIND_ADDRESS` 改为 `127.0.0.1`。公网访问应由反向代理提供 HTTPS，转发 `X-Forwarded-For` 与 `X-Forwarded-Proto`。

## 更新、查看与停止

```bash
# 上传新代码后必须重建；普通 up -d 不会重建已有镜像。
docker compose -f "$DOCKER_DIR/docker-compose.yaml" --env-file "$DOCKER_DIR/.env" up -d --build
docker compose -f "$DOCKER_DIR/docker-compose.yaml" --env-file "$DOCKER_DIR/.env" ps
docker compose -f "$DOCKER_DIR/docker-compose.yaml" --env-file "$DOCKER_DIR/.env" logs --tail=100 -f app
docker compose -f "$DOCKER_DIR/docker-compose.yaml" --env-file "$DOCKER_DIR/.env" down
```

数据库保存在 `colorful-geo-mgmt_mysql-data` 命名卷，更新和普通 down 保留数据。**不要执行 `down -v`，会删除数据库。** 初始化 SQL 只对空卷执行，已有库结构升级需要执行对应迁移脚本。初始化后更改 `.env` 数据库密码不会更改数据库账号，必须先在数据库完成密码变更。

## 数据备份与迁移

```bash
# 备份包含业务数据及模型凭据，限制访问并保存到服务器之外。
umask 077
docker compose -f "$DOCKER_DIR/docker-compose.yaml" --env-file "$DOCKER_DIR/.env" exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces colorful_geo' > colorful_geo.sql
```

默认创建空库，不自动复制本地数据。迁移应先备份，停止 app，向 MySQL 导入经核对的完整备份，确认表结构匹配当前代码再启动 app。不要把密码、平台登录态及 API Key 写入 Git 或日志。

原 install.sh 已删除；Java、Xvfb、中文字体和 Chromium 已移入镜像。当前业务使用 Playwright Chromium，不额外安装 Chrome stable，不提供远程浏览器桌面。
