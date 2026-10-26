package org.huangry.colorful.geo.infrastructure.common.utils;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 通用 JSON 能力边界测试，使用普通字段验证读写和遍历，不依赖项目迁移结构。 */
class JsonUtilsTest {
    /** 两种输入入口都拒绝重复字段、尾随文档和空内容，保留 JSON null 语义。 */
    @Test
    void shouldRejectAmbiguousDocumentsForBytesAndText() {
        for (String text : List.of("{\"value\":1,\"value\":2}", "{} {}", "", "{broken")) {
            assertThrows(IllegalArgumentException.class, () -> JsonUtils.readTree(text));
            assertThrows(IllegalArgumentException.class, () -> JsonUtils.readTree(text.getBytes(StandardCharsets.UTF_8)));
        }
        assertTrue(JsonUtils.readTree("null").isNull());
        assertTrue(JsonUtils.readTree("null".getBytes(StandardCharsets.UTF_8)).isNull());
    }

    /** 统一 JSON 转换支持 Java 时间，输出本地时间文本并保留毫秒，Java null 仍为 JSON null。 */
    @Test
    void shouldKeepLocalDateTimeAsIsoText() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 10, 24, 12, 34, 56, 123000000);
        var node = JsonUtils.toTree(dateTime);
        assertTrue(node.isTextual());
        assertEquals("2026-10-24T12:34:56.123", node.asText());
        assertTrue(JsonUtils.toTree(null).isNull());
    }

    /** 正整数读取不能把小数、指数、溢出或错误格式隐式转换成可用标识。 */
    @Test
    void shouldPreserveLargeIntegersAndRejectLossyConversions() {
        assertEquals(Long.MAX_VALUE, JsonUtils.getPositiveLong(JsonUtils.readTree("{\"key\":\"9223372036854775807\"}"), "key"));
        assertEquals(9007199254740993L, JsonUtils.getPositiveLong(JsonUtils.readTree("{\"key\":9007199254740993}"), "key"));
        for (String value : List.of("0", "-1", "1.5", "1e3", "null", "\"01\"", "\"9223372036854775808\"")) {
            var node = JsonUtils.readTree("{\"key\":" + value + "}");
            assertThrows(IllegalArgumentException.class, () -> JsonUtils.getPositiveLong(node, "key"));
        }
    }

    /** 遍历保留数组字段上下文、支持修改当前对象，并能跳过已处理的子树。 */
    @Test
    void shouldVisitNamedArrayObjectsAndSkipSelectedSubtrees() {
        ObjectNode root = (ObjectNode) JsonUtils.readTree("{\"items\":[{\"value\":1}],\"blocked\":{\"deep\":{}}}");
        List<String> contexts = new ArrayList<>();
        JsonUtils.visitObjects(root, "", (context, object) -> {
            contexts.add(context);
            if (context.isEmpty()) {
                object.put("marker", "kept");
            }
            if (context.equals("items")) {
                object.remove("value");
                object.put("changed", true);
            }
            return !context.equals("blocked");
        });
        assertEquals(List.of("", "items", "blocked"), contexts);
        assertEquals("kept", root.path("marker").asText());
        assertFalse(root.path("items").get(0).has("value"));
        assertTrue(root.path("items").get(0).path("changed").asBoolean());
    }

    /** 格式化输出能够再次严格解析，中文、数组及大整数字符串不变。 */
    @Test
    void shouldRoundTripPrettyUtf8Json() {
        var node = JsonUtils.readTree("{\"label\":\"中文\",\"values\":[null,true,\"9007199254740993\"]}");
        byte[] bytes = JsonUtils.toPrettyBytes(node);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("中文"));
        assertEquals(node, JsonUtils.readTree(bytes));
    }
}
