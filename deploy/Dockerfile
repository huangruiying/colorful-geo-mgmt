# 在镜像内构建，不依赖宿主机 Maven 或 Java。
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -DskipTests package spring-boot:repackage dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/build/dependencies

FROM eclipse-temurin:17-jdk-noble
ENV TZ=Asia/Shanghai PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
WORKDIR /app
# 使用项目实际依赖安装对应浏览器，避免 Playwright 版本不匹配。
COPY --from=build /build/dependencies /tmp/playwright-dependencies
RUN java -cp '/tmp/playwright-dependencies/*' com.microsoft.playwright.CLI install --with-deps chromium \
    && apt-get update \
    && apt-get install -y --no-install-recommends xvfb xauth fonts-noto-cjk curl \
    && rm -rf /tmp/playwright-dependencies /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 geo \
    && chmod -R a+rX /ms-playwright
# 浏览器已在上一步安装；运行时跳过 Playwright.create() 自带的安装检查，geo 用户无权写浏览器目录。
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY --from=build --chown=geo:geo /build/target/colorful-geo-mgmt-1.0.0-SNAPSHOT.jar /app/app.jar
USER geo
EXPOSE 8010
# 虚拟显示兼容现有浏览器启动方式；由 Compose init 回收子进程。
ENTRYPOINT ["xvfb-run", "-a", "-s", "-screen 0 1920x1080x24", "java", "-jar", "/app/app.jar"]
