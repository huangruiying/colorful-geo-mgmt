package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import org.huangry.colorful.geo.infrastructure.client.llm.config.LLMProviderProperties;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * 大模型 HTTP 客户端创建工具。
 *
 * <p>默认使用 JDK 证书校验；仅当供应商显式关闭 sslVerify 时才构造不校验证书的客户端。</p>
 *
 * @author huangry
 */
final class LlmHttpClientFactory {

    private LlmHttpClientFactory() {
    }

    /**
     * 构建 LangChain4j 使用的 HTTP 客户端。
     *
     * @param provider 供应商配置
     * @return 配置完成的客户端构造器
     */
    static JdkHttpClientBuilder createLangChain4jClientBuilder(LLMProviderProperties.Provider provider) {
        Duration timeout = Duration.ofSeconds(provider.getTimeoutSeconds());
        return JdkHttpClient.builder()
                .httpClientBuilder(createJavaHttpClientBuilder(provider, timeout))
                .connectTimeout(timeout)
                .readTimeout(timeout);
    }

    /**
     * 构建 Responses API 使用的 Spring 请求工厂。
     *
     * @param provider 供应商配置
     * @return 请求工厂
     */
    static JdkClientHttpRequestFactory createSpringRequestFactory(LLMProviderProperties.Provider provider) {
        Duration timeout = Duration.ofSeconds(provider.getTimeoutSeconds());
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                createJavaHttpClientBuilder(provider, timeout).build());
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    /**
     * 创建底层 JDK HTTP 客户端构造器。
     *
     * <p>证书校验是默认安全边界；只有内部网关明确声明 sslVerify=false 时才进入不安全兼容分支。</p>
     *
     * @param provider 供应商连接配置
     * @param timeout 连接与读取超时
     * @return JDK HTTP 客户端构造器
     */
    private static HttpClient.Builder createJavaHttpClientBuilder(LLMProviderProperties.Provider provider, Duration timeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(timeout);
        if (provider.isSslVerify()) {
            return builder;
        }

        // 兼容内部网关的自签名证书。该分支会跳过服务端身份校验，只能由显式配置触发。
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm(null);
        return builder.sslContext(createUnsafeSslContext()).sslParameters(sslParameters);
    }

    /**
     * 创建跳过证书链校验的 SSL 上下文。
     *
     * <p>该方法只服务于显式关闭证书校验的内部网关兼容场景，调用方不得作为公网默认配置。</p>
     *
     * @return 不校验证书的 SSL 上下文
     */
    private static SSLContext createUnsafeSslContext() {
        try {
            TrustManager[] trustAllCertificates = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCertificates, new SecureRandom());
            return sslContext;
        } catch (GeneralSecurityException exception) {
            throw new LLMClientException("无法创建关闭证书校验的大模型 HTTP 客户端", exception);
        }
    }
}
