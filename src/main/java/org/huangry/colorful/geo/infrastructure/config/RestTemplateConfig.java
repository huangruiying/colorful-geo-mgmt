package org.huangry.colorful.geo.infrastructure.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import java.security.GeneralSecurityException;

/**
 * 注册具名的大模型 HTTP 客户端和 RestTemplate，调用方直接按名称注入。
 * <p>超时、证书和连接池统一配置，不在请求期间创建或修改客户端；
 * Spring 关闭 HttpClient 时释放连接池和回收线程。</p>
 */
@Configuration
public class RestTemplateConfig {
    /** 模型 HTTP 获取连接、建立连接及响应等待的统一超时秒数。 */
    @Value("${http.llm.timeout-seconds}")
    private int timeoutSeconds;
    /** 模型 HTTP 是否校验证书链和主机名。 */
    @Value("${http.llm.ssl-verify}")
    private boolean sslVerify;

    /**
     * 提前注册模型专用连接池客户端，不保存密钥，不自动重试或重定向。
     * @return 应用停止时由 Spring 关闭的 HttpClient
     */
    @Bean(name = "llmHttpClient", destroyMethod = "close")
    public CloseableHttpClient llmHttpClient() {
        // 1. 统一配置必须有效，不能以零超时无限等待。
        if (timeoutSeconds < 1) throw new IllegalArgumentException("HTTP 超时秒数必须大于零");
        Timeout timeout = Timeout.ofSeconds(timeoutSeconds);
        var requestConfig = RequestConfig.custom().setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout).build();
        // 2. 只有明确关闭证书校验才信任任意证书，不改变 JVM 全局信任配置。
        var ssl = SSLConnectionSocketFactoryBuilder.create();
        if (!sslVerify) {
            try {
                ssl.setSslContext(SSLContexts.custom().loadTrustMaterial((chain, authType) -> true).build())
                        .setHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("无法创建 HTTP 证书兼容配置", exception);
            }
        }
        // 3. 池随客户端创建一次，内置回收闲置连接，不另建调度器。
        var pool = PoolingHttpClientConnectionManagerBuilder.create().setSSLSocketFactory(ssl.build())
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).build())
                .setMaxConnTotal(100).setMaxConnPerRoute(20).build();
        return HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }

    /**
     * 提前注册模型专用 RestTemplate，协议模板按 Bean 名注入同一实例。
     * @return 使用 llmHttpClient 连接池和统一超时的 RestTemplate
     */
    @Bean("llmRestTemplateWithTimeout")
    public RestTemplate llmRestTemplateWithTimeout() {
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(llmHttpClient()));
    }
}
