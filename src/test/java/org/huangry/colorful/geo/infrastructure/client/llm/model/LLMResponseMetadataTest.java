package org.huangry.colorful.geo.infrastructure.client.llm.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 元数据用于诊断落库，既有问答响应结构和凭据隔离保持不变。 */
class LLMResponseMetadataTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 元数据子对象也不能保存认证字段；特殊字符和数字密钥不能破坏 JSON 或用量。 */
    @Test
    void shouldRedactUsageAndEscapedCredentialWithoutChangingNumericUsage() throws Exception {
        var source = mapper.readTree("""
                {"usage":{"total_tokens":42,"api_key":"private","note":"42"},"text":"42"}
                """);
        var result = new LLMResponse("回答 42", List.of()).withMetadata(source, "stop").withoutCredential(" 42 ");
        assertFalse(result.content().contains("42"));
        assertFalse(result.usageInfo().contains("private"));
        assertEquals(42, mapper.readTree(result.usageInfo()).path("total_tokens").asInt());
        assertEquals("[REDACTED]", mapper.readTree(result.rawResponse()).path("text").asText());
        String key = "quote\"key";
        var escaped = mapper.createObjectNode().put("text", key);
        var quotedResult = new LLMResponse("回答", List.of()).withMetadata(escaped, null).withoutCredential(key);
        assertEquals("[REDACTED]", mapper.readTree(quotedResult.rawResponse()).path("text").asText());
    }

    /** 原始响应保留用量，嵌套认证字段与当前密钥回显不能落库。 */
    @Test
    void shouldPreserveMetadataAndRemoveNestedSecrets() throws Exception {
        var response = mapper.readTree("""
                {"model":"actual-model","usage":{"total_tokens":42},"api_key":"private",
                 "nested":[{"Authorization":"private","text":"echo qa-key"}],"status":"completed"}
                """);
        var result = new LLMResponse("回答", List.of()).withMetadata(response, "completed").withoutCredential("qa-key");
        assertEquals("actual-model", result.modelName());
        assertEquals(42, mapper.readTree(result.usageInfo()).path("total_tokens").asInt());
        assertEquals("completed", result.finishReason());
        assertFalse(result.rawResponse().contains("private"));
        assertFalse(result.rawResponse().contains("qa-key"));
        assertTrue(response.has("api_key"));
    }

    /** 普通接口继续只输出正文与引用，未返回字段也不伪造。 */
    @Test
    void shouldKeepPublicQueryJsonCompatibleAndMissingMetadataNull() throws Exception {
        var result = new LLMResponse("回答", List.of()).withMetadata(mapper.readTree("{}"), null);
        var json = mapper.valueToTree(result);
        assertEquals(2, json.size());
        assertEquals("回答", json.path("content").asText());
        assertTrue(json.path("citations").isArray());
        assertNull(result.modelName()); assertNull(result.usageInfo()); assertNull(result.finishReason());
    }
}
