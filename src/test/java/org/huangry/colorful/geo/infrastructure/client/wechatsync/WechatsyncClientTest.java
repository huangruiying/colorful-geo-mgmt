package org.huangry.colorful.geo.infrastructure.client.wechatsync;

import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.mockito.ArgumentCaptor;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Wechatsync 的配置、参数传递与本地进程生命周期测试，不访问真实平台或浏览器扩展。
 * @author huangry
 */
class WechatsyncClientTest {

    /** 命令中的引号及 shell 特殊字符需要保持字面含义，环境凭证不得进入命令日志。 */
    @Test
    void 命令日志应引用参数且不包含环境Token() {
        WechatsyncClient client = new WechatsyncClient(properties());
        ProcessBuilder builder = new ProcessBuilder("/path with space/wechatsync", "--title=it's $(id)");
        builder.environment().put("WECHATSYNC_TOKEN", "test-bridge-token");
        String command = client.formatCommand(builder);
        assertEquals("'/path with space/wechatsync' '--title=it'\"'\"'s $(id)'", command);
        assertFalse(command.contains("test-bridge-token"));
    }

    /** 凭证仅通过子进程环境传递，平台和标题保持独立命令参数。 */
    @Test
    void 应传递配置Token和目标平台而不拼接Shell() {
        WechatsyncProperties properties = properties();
        WechatsyncClient client = new WechatsyncClient(properties);
        String title = "标题 $(touch ignored)";
        ProcessBuilder command = client.createContentPublishCommand("wordpress", title, Path.of("article.md"));
        assertEquals("test-bridge-token", command.environment().get("WECHATSYNC_TOKEN"));
        assertFalse(command.command().contains("test-bridge-token"));
        assertEquals("wordpress", command.command().get(6));
        assertEquals("--title=" + title, command.command().get(7));
        assertEquals(properties.getExecutable(), command.command().get(0));
        ProcessBuilder authCommand = client.createCheckLoginCommand("wordpress");
        assertEquals("test-bridge-token", authCommand.environment().get("WECHATSYNC_TOKEN"));
        assertEquals("auth", authCommand.command().get(3));
        assertEquals("wordpress", authCommand.command().get(4));
    }

    /** 无效超时不能进入外部执行。 */
    @Test
    void 应拒绝无效执行配置() {
        WechatsyncProperties properties = properties();
        properties.setTimeoutSeconds(0);
        assertThrows(PublicationClientException.class, () -> new WechatsyncClient(properties)
                .createContentPublishCommand("zhihu", "标题", Path.of("article.md")));
        properties.setExecutable(null);
        assertThrows(PublicationClientException.class, () -> new WechatsyncClient(properties)
                .createCheckLoginCommand("zhihu"));
    }

    /** 只使用本地替身进程，验证返回输出后正文被清理。 */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 同步应返回输出并删除临时正文() throws Exception {
        WechatsyncClient client = spy(new WechatsyncClient(properties()));
        stubAuthorized(client, "zhihu");
        doReturn(new ProcessBuilder("/bin/sh", "-c", "printf success"))
                .when(client).createContentPublishCommand(eq("zhihu"), eq("标题"), any());
        assertEquals("success", client.sync("zhihu", "标题", "正文"));
        ArgumentCaptor<Path> file = ArgumentCaptor.forClass(Path.class);
        verify(client).createContentPublishCommand(eq("zhihu"), eq("标题"), file.capture());
        assertFalse(Files.exists(file.getValue()));
    }

    /** 失败也需要清理正文并释放锁，允许下一次调用。 */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 同步失败应清理文件并释放互斥锁() {
        WechatsyncClient client = spy(new WechatsyncClient(properties()));
        stubAuthorized(client, "zhihu");
        doReturn(new ProcessBuilder("/bin/sh", "-c", "exit 2"))
                .when(client).createContentPublishCommand(anyString(), anyString(), any());
        assertThrows(PublicationClientException.class, () -> client.sync("zhihu", "标题", "正文"));
        ArgumentCaptor<Path> file = ArgumentCaptor.forClass(Path.class);
        verify(client).createContentPublishCommand(anyString(), anyString(), file.capture());
        assertFalse(Files.exists(file.getValue()));
        stubAuthorized(client, "wordpress");
        doReturn(new ProcessBuilder("/bin/sh", "-c", "printf recovered"))
                .when(client).createContentPublishCommand(anyString(), anyString(), any());
        assertEquals("recovered", client.sync("wordpress", "标题", "正文"));
    }

    /** 登录检查虽正常退出，但平台未登录时不得提交正文。 */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 平台未登录时不应同步正文() {
        WechatsyncClient client = spy(new WechatsyncClient(properties()));
        doReturn(new ProcessBuilder("/bin/echo", "Chrome Extension 已连接\n✗ zhihu 未登录"))
                .when(client).createCheckLoginCommand("zhihu");

        PublicationClientException error = assertThrows(PublicationClientException.class,
                () -> client.sync("zhihu", "标题", "正文"));

        assertTrue(error.getMessage().contains("未登录"));
        verify(client, never()).createContentPublishCommand(anyString(), anyString(), any());
    }

    /** 扩展未连接时给出明确原因，不把检查结果当成已登录。 */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 扩展未连接时不应同步正文() {
        WechatsyncClient client = spy(new WechatsyncClient(properties()));
        doReturn(new ProcessBuilder("/bin/echo", "连接超时"))
                .when(client).createCheckLoginCommand("zhihu");

        PublicationClientException error = assertThrows(PublicationClientException.class,
                () -> client.sync("zhihu", "标题", "正文"));

        assertTrue(error.getMessage().contains("扩展未连接"));
        verify(client, never()).createContentPublishCommand(anyString(), anyString(), any());
    }

    /** 登录预检查有独立超时，不能一直占用正文同步的执行时间。 */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 登录检查超时时不应同步正文() {
        WechatsyncProperties properties = properties();
        properties.setAuthTimeoutSeconds(1);
        WechatsyncClient client = spy(new WechatsyncClient(properties));
        doReturn(new ProcessBuilder("/bin/sh", "-c", "sleep 10"))
                .when(client).createCheckLoginCommand("zhihu");

        PublicationClientException error = assertThrows(PublicationClientException.class,
                () -> client.sync("zhihu", "标题", "正文"));

        assertTrue(error.getMessage().contains("登录检查超时"));
        verify(client, never()).createContentPublishCommand(anyString(), anyString(), any());
    }

    /** 超时不能宣称平台未收到请求，也不自动重试。 */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 超时应提示先核对草稿箱() {
        WechatsyncProperties properties = properties();
        properties.setTimeoutSeconds(1);
        PublicationOutcomeUnknownException error = assertThrows(PublicationOutcomeUnknownException.class,
                () -> new WechatsyncClient(properties).runCommand(
                        new ProcessBuilder("/bin/sh", "-c", "sleep 10")));
        assertTrue(error.getMessage().contains("草稿可能已创建"));
    }

    /** 正文提交结果不确定时不能重新提交，避免平台生成重复草稿。 */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 正文同步超时不得自动重试() {
        WechatsyncProperties properties = properties();
        properties.setTimeoutSeconds(1);
        WechatsyncClient client = spy(new WechatsyncClient(properties));
        stubAuthorized(client, "zhihu");
        doReturn(new ProcessBuilder("/bin/sh", "-c", "sleep 3"))
                .when(client).createContentPublishCommand(eq("zhihu"), eq("标题"), any());

        assertThrows(PublicationOutcomeUnknownException.class, () -> client.sync("zhihu", "标题", "正文"));

        verify(client, times(1)).createContentPublishCommand(eq("zhihu"), eq("标题"), any());
        verify(client, never()).createWakeChromeExtensionCommand();
    }

    /** 缺失 CLI 时给出安装和运行环境提示。 */
    @Test
    void 缺失CLI应提示安装与路径() {
        PublicationClientException error = assertThrows(PublicationClientException.class,
                () -> new WechatsyncClient(properties()).runCommand(
                        new ProcessBuilder("/nonexistent-geo-test/wechatsync")));
        assertTrue(error.getMessage().contains("无法启动 Wechatsync CLI"));
    }

    /** 测试使用虚构凭证，避免依赖真实本地 Token。 */
    private WechatsyncProperties properties() {
        WechatsyncProperties properties = new WechatsyncProperties();
        properties.setToken("test-bridge-token");
        return properties;
    }

    /** 模拟使用同一 CLI 返回扩展已连接及目标平台已登录。 */
    private void stubAuthorized(WechatsyncClient client, String platform) {
        doReturn(new ProcessBuilder("/bin/echo", "Chrome Extension 已连接\n✓ " + platform + " 已登录"))
                .when(client).createCheckLoginCommand(platform);
    }
}
