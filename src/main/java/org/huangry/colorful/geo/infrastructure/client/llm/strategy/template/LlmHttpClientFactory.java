package org.huangry.colorful.geo.infrastructure.client.llm.strategy.template;

import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
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
 * 大模型 HTTP 请求工厂，createSpringRequestFactory 应用渠道超时与证书配置。
 *
 * <p>默认使用 JDK 证书校验；仅当供应商显式关闭 sslVerify 时才构造不校验证书的客户端。</p>
 *
 * @author huangry
 */
final class LlmHttpClientFactory {

    /** 仅提供静态工厂，不创建工具实例。 */
    private LlmHttpClientFactory() {
    }

    /**
     * 构建两种协议共用的 Spring 请求工厂。
     *
     * @param channel 渠道超时和证书配置，超时秒数须已校验
     * @return 请求工厂
     */
    static JdkClientHttpRequestFactory createSpringRequestFactory(GeoLlmChannelEntity channel) {
        Duration timeout = Duration.ofSeconds(channel.getTimeoutSeconds());
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                createJavaHttpClientBuilder(channel, timeout).build());
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    /**
     * 创建底层 JDK HTTP 客户端构造器。
     *
     * <p>证书校验是默认安全边界；只有内部网关明确声明 sslVerify=false 时才进入不安全兼容分支。</p>
     *
     * @param channel 渠道证书校验配置
     * @param timeout 连接与读取超时
     * @return JDK HTTP 客户端构造器
     */
    private static HttpClient.Builder createJavaHttpClientBuilder(GeoLlmChannelEntity channel, Duration timeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(timeout);
        // 未配置或显式启用均保留 JDK 默认校验，只有 false 进入兼容分支。
        if (!Boolean.FALSE.equals(channel.getSslVerify())) {
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
            // 1. 为显式关闭校验的渠道构造不验证证书链的信任管理器。
            TrustManager[] trustAllCertificates = new TrustManager[]{new X509TrustManager() {
                /** 客户端证书不作校验，仅用于显式关闭校验的连接。 */
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                /** 服务端证书链不作校验，不能据此认定远端身份可信。 */
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                /** 不限制接受的证书颁发机构。 */
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            // 2. 将信任管理器绑定到独立 TLS 上下文，不修改 JVM 默认配置。
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCertificates, new SecureRandom());
            return sslContext;
        } catch (GeneralSecurityException exception) {
            // 上下文创建失败阻断连接，不能悄悄改用其他证书策略。
            throw new LLMClientException("无法创建关闭证书校验的大模型 HTTP 客户端", exception);
        }
    }
}
