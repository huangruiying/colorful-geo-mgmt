package org.huangry.colorful.geo.infrastructure.client.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderName;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大模型通道配置。
 *
 * <p>负责绑定启用通道及各供应商的连接参数，不负责解析密钥或发起调用。</p>
 *
 * @author huangry
 */
@Setter
@Component
@ConfigurationProperties(prefix = "colorful.geo.llm")
@Getter
public class LLMProviderProperties {

	/** 当前启用的通道名称，须对应 providers 中的键；未配置时默认使用 mock。 */
	private String enabledLlmProvider = LLMProviderName.MOCK.configurationName();

	/** 按通道名称索引的供应商配置；允许自定义名称，供路由器选择当前通道。 */
	private Map<String, Provider> providers = new LinkedHashMap<>();

	/**
	 * 单个大模型供应商的连接配置。
	 *
	 * <p>apiKey 仅保留为本地兜底，生产环境应使用 apiKeyEnv 指向的环境变量。</p>
	 */
	@Getter
	@Setter
	public static class Provider {

		/** 调用协议类型，默认本地模拟；用于选择模型调用策略。 */
		private LLMProviderType type = LLMProviderType.MOCK;

		/** 通道的说明文字，仅用于配置说明，不参与策略选择。 */
		private String description;

		/** 远程服务根地址，包含所需版本前缀；具体接口路径由调用策略处理。 */
		private String baseUrl;

		/** 配置中的访问密钥；仅在 apiKeyEnv 指定的环境变量没有有效值时使用，禁止写入日志。 */
		private String apiKey;

		/** 保存访问密钥的环境变量名称，例如 OPENAI_API_KEY；此字段不是密钥本身。 */
		private String apiKeyEnv;

		/** 当前通道使用的模型标识，由供应商定义，远程调用时传给模型接口。 */
		private String model;

		/** 生成随机性参数，默认 0.3；当前普通兼容协议策略会传递，网页搜索策略未传递。 */
		private Double temperature = 0.3D;

		/** 输出 token 数量上限，默认 1400；当前普通兼容协议策略会传递，网页搜索策略未传递。 */
		private Integer maxTokens = 1400;

		/** 远程请求超时时间，单位秒，默认 60；用于构建模型客户端与 HTTP 请求配置。 */
		private Integer timeoutSeconds = 60;

		/** 是否校验 HTTPS 证书，默认开启；关闭时由 HTTP 客户端工厂使用宽松证书信任配置。 */
		private boolean sslVerify = true;

		/** 当前通道是否支持 Responses API 的网页搜索工具；用于路由选择，不保证每次调用执行搜索或返回引用。 */
		private boolean webSearchSupported;
	}
}
