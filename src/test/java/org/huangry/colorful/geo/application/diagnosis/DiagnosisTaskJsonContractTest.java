package org.huangry.colorful.geo.application.diagnosis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisTaskResponse;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 Fastjson 业务对象仍由 Spring MVC 的 Jackson 序列化，保持对外 JSON 结构。 */
class DiagnosisTaskJsonContractTest {
    /** 对象、数组、显式空值和零值保持 JSON 类型，不变成字符串或库内部属性。 */
    @Test
    void shouldKeepSnapshotAndResultJsonShapesAtMvcBoundary() throws Exception {
        var mapper = new ObjectMapper();
        var snapshot = JSON.parseObject("{\"planName\":\"方案\",\"channels\":[{\"temperature\":null}]}");
        var progress = new DiagnosisTaskResponse.ProgressResponse(1, 0, 0, 1, 0);
        var task = new DiagnosisTaskResponse(1L, 2L, "方案", 3L, "项目", "SUCCEEDED", progress,
                List.of(), snapshot, null, null, null);
        var taskJson = mapper.readTree(mapper.writeValueAsString(task));
        assertEquals("方案", taskJson.path("snapshot").path("planName").asText());
        assertTrue(taskJson.path("snapshot").path("channels").isArray());
        assertTrue(taskJson.path("snapshot").path("channels").path(0).path("temperature").isNull());
        assertFalse(taskJson.has("startedAt"));
        var result = new DiagnosisTaskResponse.ResultResponse(4L, 1L, 1, "问题", new JSONObject(),
                5L, "渠道", new JSONObject(), 1, "SUCCEEDED", "回答",
                JSON.parseArray("[{\"url\":\"https://example.invalid\",\"startIndex\":null}]"), "model",
                JSON.parseObject("{\"total_tokens\":0}"), 10L, "stop", "{}", null, null, null);
        var resultJson = mapper.readTree(mapper.writeValueAsString(result));
        assertTrue(resultJson.path("citations").isArray());
        assertEquals("https://example.invalid", resultJson.path("citations").path(0).path("url").asText());
        assertTrue(resultJson.path("citations").path(0).path("startIndex").isNull());
        assertTrue(resultJson.path("questionSnapshot").isObject());
        assertTrue(resultJson.path("usageInfo").path("total_tokens").isInt());
        assertEquals(0, resultJson.path("usageInfo").path("total_tokens").asInt());
    }
}
