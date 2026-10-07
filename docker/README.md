# 本地 MySQL

本项目使用 Docker Compose 启动 MySQL 8.4。容器仅监听本机 `127.0.0.1:3306`，数据保存在 Compose 命名卷中；建表 SQL 位于 `src/main/resources/db/mysql/001_init.sql`，空数据卷首次启动时自动执行。

1. 启动 Docker 或 OrbStack。
2. 在本目录创建不提交到 Git 的 `.env`，填入 `MYSQL_ROOT_PASSWORD` 和 `MYSQL_PASSWORD` 两个不同的随机密码。
3. 在项目模块目录执行 `docker compose -f docker/compose.yml up -d`，用 `docker compose -f docker/compose.yml ps` 确认健康状态。
4. 启动 Java 服务前设置 `GEO_MYSQL_PASSWORD` 为 `.env` 中的 `MYSQL_PASSWORD`。默认连接地址是 `jdbc:mysql://127.0.0.1:3306/colorful_geo`，账号是 `geo_app`；需要连接其他数据库时设置 `GEO_MYSQL_URL`、`GEO_MYSQL_USER`。

注意：初始化目录中的 SQL 只在**空数据卷首次创建**时自动执行。已经运行过的数据库需要手动执行后续迁移 SQL，不能通过删除数据卷重新初始化。

旧库从 `content_publish_task` 升级时，先备份数据库，再在项目模块目录执行一次：

```bash
docker compose -f docker/compose.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ugeo_app -Dcolorful_geo' < src/main/resources/db/mysql/002_content_publish_record.sql
```

迁移脚本保留旧优化记录中的人工稿和审核状态列以保护历史数据；新代码不再使用这两列。新建空库只需执行 `001_init.sql`，不要再执行 `002`。
