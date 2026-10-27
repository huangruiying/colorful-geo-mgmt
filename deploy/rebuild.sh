#!/bin/sh
# 重新构建并启动服务；构建成功后清理旧版本镜像和过期构建缓存，避免磁盘逐次占满。
set -e

docker compose -f "docker-compose.yaml" --env-file ".env" up -d --build

# 只删除无名称的旧版本镜像，不删除基础镜像和数据卷
docker image prune -f
# 只删除 7 天未使用的构建缓存，保留近期 Maven 依赖与 Playwright 安装层，下次构建仍可复用
docker builder prune -f --filter until=168h

df -h /var/lib
