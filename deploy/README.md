# Linux 浏览器环境安装

仅支持 **Ubuntu 22.04 / 24.04，x86_64**，需要联网下载 Ubuntu 软件包及 Google Chrome。

在项目根目录执行一次：

```bash
sudo bash Deploy/install.sh
```

安装内容：JDK 17、Google Chrome stable、Xvfb、Xauth、中文字体及 Chrome 必要系统依赖。JDK 使用 Ubuntu 的 OpenJDK 17，不是本机 Microsoft 17.0.17；Chrome 使用执行安装时的 stable 版本。

脚本不启动服务，不修改 `.env`，不操作数据库。重复执行会检查或更新安装的软件；不要放进每次业务服务启动流程。

无界面服务器启动有界面浏览器时，可用虚拟显示包装 Java 进程（先打包生成 JAR）：

```bash
xvfb-run -a -s '-screen 0 1920x1080x24' /usr/lib/jvm/java-17-openjdk-amd64/bin/java -jar target/colorful-geo-mgmt-1.0.0-SNAPSHOT.jar
```

JAR 文件名以实际打包产物为准。Xvfb 不会自动把浏览器切换为有界面模式；头条的 Chrome + CDP 模式还需业务代码接入及 Linux 真实联调。安装完成不代表头条预发布已验证成功。
