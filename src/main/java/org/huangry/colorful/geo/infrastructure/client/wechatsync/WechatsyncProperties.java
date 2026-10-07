package org.huangry.colorful.geo.infrastructure.client.wechatsync;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Wechatsync 客户端配置，供各平台策略共用。
 *
 * <p>通过 Getter 提供 Token、CLI 路径和超时；不承担平台业务，不输出凭证。</p>
 *
 * @author huangry
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "colorful.geo.wechatsync")
public class WechatsyncProperties {
	/**
	 * 扩展桥接 Token；留空时读取 WECHATSYNC_TOKEN 环境变量，真实值不得提交仓库。
	 */
	private String token = "e279e94c-4214-4003-8a3f-21f32acbc23f";
	/**
	 * Wechatsync CLI 1.1.0 可执行文件路径；必须提前安装，不接受拼接的 shell 命令。
	 */
	private String executable = "/opt/homebrew/bin/wechatsync";
	/**
	 * 等待扩展连接的超时，单位毫秒。
	 */
	private int connectionTimeoutMillis = 30000;
	/**
	 * 平台登录查询与预检查的单次超时，单位秒；不代表正文同步总超时。
	 */
	private int authTimeoutSeconds = 45;
	/**
	 * macOS 登录检查超时后用于唤醒文章同步助手的页面；留空则不自动唤醒。
	 */
	private String extensionPopupUrl = "chrome-extension://hchobocdmclopcbnibdnoafilagadion/src/popup/index.html";
	/**
	 * 单次 CLI 执行总超时，单位秒；超时后的远程草稿状态需要人工核对。
	 */
	private int timeoutSeconds = 120;
}
