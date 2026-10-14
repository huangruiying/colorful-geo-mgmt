package org.huangry.colorful.geo.infrastructure.client.llm.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 验证普通结果类保持原问答 JSON 契约，诊断字段仅由内部读取。 */
class LLMResponseTest {
    /** 填充诊断字段不会扩展既有问答接口的正文、引用结构。 */
    @Test
    void shouldKeepPublicQueryJsonCompatible() {
        var result = new LLMResponse("回答", List.of());
        result.setModelName("actual-model");
        result.setUsageInfo("{\"total_tokens\":42}");
        result.setFinishReason("completed");
        result.setRawResponse("{}");
        var json = new ObjectMapper().valueToTree(result);
        assertEquals(2, json.size());
        assertEquals("回答", json.path("content").asText());
        assertTrue(json.path("citations").isArray());
    }
}
