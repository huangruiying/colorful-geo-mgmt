package org.huangry.colorful.geo.infrastructure.config;

import com.sun.net.httpserver.HttpServer;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.CompatibleChatCompletionsApiStrategy;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.DoubaoResponsesApiStrategy;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.OpenAiResponsesApiStrategy;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.CompatibleResponsesApiStrategy;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 验证共享连接、独立超时和模型 HTTP 回读，使用本地服务，不调用外部平台。 */
class RestTemplateConfigTest {
    /** 按名称获取同一 Bean，底层连接池由预注册的客户端管理。 */
    @Test
    void shouldSharePoolsAndKeepCertificatePoliciesSeparate() {
        try (var context = context(60)) {
            var template = context.getBean("llmRestTemplateWithTimeout", RestTemplate.class);
            assertSame(template, context.getBean("llmRestTemplateWithTimeout"));
            var factory = (HttpComponentsClientHttpRequestFactory) template.getRequestFactory();
            assertSame(context.getBean("llmHttpClient"), factory.getHttpClient());
            assertEquals(1, context.getBeansOfType(RestTemplate.class).size());
        }
    }

    /** 两次独立配置复用实际 TCP 连接，避免每次新建连接池。 */
    @Test
    void shouldReuseActualConnectionAcrossTemplates() throws Exception {
        var port = new AtomicInteger();
        var reused = new AtomicReference<Boolean>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reuse", exchange -> {
            int remotePort = exchange.getRemoteAddress().getPort();
            if (port.get() == 0) port.set(remotePort); else reused.set(port.get() == remotePort);
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var context = context(60)) {
            var template = context.getBean("llmRestTemplateWithTimeout", RestTemplate.class);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/reuse";
            assertEquals("ok", template.getForObject(url, String.class));
            assertEquals("ok", template.getForObject(url, String.class));
            assertEquals(Boolean.TRUE, reused.get());
        } finally { server.stop(0); }
    }

    /** 预注册客户端使用统一响应超时，也不自动重复请求。 */
    @Test
    void shouldKeepResponseTimeoutIndependentAndAvoidRetry() throws Exception {
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            calls.incrementAndGet();
            try { Thread.sleep(1400); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            try {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try (var context = context(1)) {
            var template = context.getBean("llmRestTemplateWithTimeout", RestTemplate.class);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
            assertThrows(ResourceAccessException.class, () -> template.getForObject(url, String.class));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    /** 两种协议经 Spring 注入后发送原请求，并回读结构化引用。 */
    @Test
    void shouldPreserveModelPayloadAndCitations() throws Exception {
        var payload = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String json = exchange.getRequestURI().getPath().endsWith("responses")
                    ? "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"回答\",\"annotations\":[{\"type\":\"url_citation\",\"url\":\"https://source.example\"}]}]}]}"
                    : "{\"choices\":[{\"message\":{\"content\":\"回答\",\"annotations\":[{\"type\":\"url_citation\",\"url_citation\":{\"url\":\"https://source.example\"}}]}}]}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var context = context(3,
                CompatibleChatCompletionsApiStrategy.class, DoubaoResponsesApiStrategy.class,
                OpenAiResponsesApiStrategy.class, CompatibleResponsesApiStrategy.class)) {
            var channel = new GeoLlmChannelEntity();
            channel.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            channel.setModelName("test-model");
            channel.setApiKey("local-test-only");
            channel.setTimeoutSeconds(3);
            channel.setSslVerify(true);
            channel.setTemperature(0.2);
            channel.setMaxTokens(300);
            var chat = context.getBean(CompatibleChatCompletionsApiStrategy.class).query(new LLMQueryRequest("问题"), channel);
            assertEquals("回答", chat.content());
            assertEquals("https://source.example", chat.citations().get(0).url());
            assertTrue(payload.get().contains("messages"));
            assertEquals("Bearer local-test-only", auth.get());
            var responses = context.getBean(DoubaoResponsesApiStrategy.class).query(new LLMQueryRequest("问题"), channel);
            assertEquals("回答", responses.content());
            assertEquals("https://source.example", responses.citations().get(0).url());
            assertTrue(payload.get().contains("web_search"));
            assertTrue(payload.get().contains("required"));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var body = mapper.readTree(payload.get());
            assertEquals(300, body.path("max_output_tokens").asInt());
            assertEquals(0.2, body.path("temperature").asDouble());
            assertFalse(body.has("max_tokens"));
            // OpenAI 普通模型传随机性；未配置推理模式的已知推理系列只传输出上限。
            channel.setModelName("gpt-4.1");
            context.getBean(OpenAiResponsesApiStrategy.class).query(new LLMQueryRequest("问题"), channel);
            assertEquals(0.2, mapper.readTree(payload.get()).path("temperature").asDouble());
            channel.setModelName("gpt-5-mini");
            context.getBean(OpenAiResponsesApiStrategy.class).query(new LLMQueryRequest("问题"), channel);
            body = mapper.readTree(payload.get());
            assertFalse(body.has("temperature"));
            assertEquals(300, body.path("max_output_tokens").asInt());
            // 兼容网关不借用 OpenAI 模型名称的限制，不声明厂商搜索工具。
            context.getBean(CompatibleResponsesApiStrategy.class).query(new LLMQueryRequest("问题"), channel);
            body = mapper.readTree(payload.get());
            assertEquals(0.2, body.path("temperature").asDouble());
            assertEquals(300, body.path("max_output_tokens").asInt());
            assertFalse(body.has("tools"));
        } finally { server.stop(0); }
    }

    /** 使用显式测试配置注册具名客户端及可选策略，不连接外部模型。 */
    private AnnotationConfigApplicationContext context(int timeout, Class<?>... strategies) {
        var context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "http.llm.timeout-seconds=" + timeout, "http.llm.ssl-verify=true");
        context.register(RestTemplateConfig.class);
        if (strategies.length > 0) context.register(strategies);
        context.refresh();
        return context;
    }
}
