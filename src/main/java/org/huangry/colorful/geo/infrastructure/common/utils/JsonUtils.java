package org.huangry.colorful.geo.infrastructure.common.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.BiPredicate;

/** 通用 JSON 树读写与遍历；复用 Spring 的 Jackson 配置构建能力，不承载文件大小、表结构或业务字段规则。 */
public final class JsonUtils {
    /** 初始化后不再修改配置；Spring 构建器自动加载现有 Java 时间模块。 */
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    /** 仅解析树，拒绝重复字段和尾随内容，不根据输入类型名实例化对象。 */
    private static final ObjectReader TREE_READER = MAPPER.readerFor(JsonNode.class)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 静态工具不创建实例，配置只在类加载时初始化。 */
    private JsonUtils() { }

    /**
     * 严格解析 UTF-8 JSON；空文档、重复字段和语法错误均拒绝，允许 JSON 字面量 null。
     * @param bytes JSON 字节
     * @return JSON 树
     * @throws IllegalArgumentException 内容不是完整有效的 JSON
     */
    public static JsonNode readTree(byte[] bytes) {
        try {
            JsonNode node = TREE_READER.readTree(bytes);
            if (node == null || node.isMissingNode()) {
                throw new IllegalArgumentException("JSON 内容不能为空");
            }
            return node;
        } catch (IOException exception) {
            throw new IllegalArgumentException("JSON 格式无效", exception);
        }
    }

    /**
     * 严格解析 JSON 文本，不把文本当作 Java 类型描述。
     * @param text JSON 文本
     * @return JSON 树
     * @throws IllegalArgumentException 内容不是完整有效的 JSON
     */
    public static JsonNode readTree(String text) {
        try {
            JsonNode node = TREE_READER.readTree(text);
            if (node == null || node.isMissingNode()) {
                throw new IllegalArgumentException("JSON 内容不能为空");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON 格式无效", exception);
        }
    }

    /**
     * 序列化为便于阅读的 UTF-8 JSON；体积及业务限制由调用者处理。
     * @param node JSON 树
     * @return JSON 字节
     * @throws IllegalStateException 无法序列化
     */
    public static byte[] toPrettyBytes(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 序列化失败", exception);
        }
    }

    /**
     * 将可信的程序值转成 JSON 树；日期时间输出 ISO 文本，不用此方法筛选接口敏感字段。
     * @param value 已由调用者选择的值
     * @return 对应 JSON 值，Java null 转成 JSON null
     */
    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * 读取正整数 long 字段，支持整数值和十进制字符串；不接受缺失、小数、指数写法、前导零或溢出。
     * @param object 所属 JSON 对象
     * @param field 字段名
     * @return 正整数值
     * @throws IllegalArgumentException 字段格式错误或超出 long 范围
     */
    public static long getPositiveLong(JsonNode object, String field) {
        return confirmPositiveLong(object.get(field), field);
    }

    /**
     * 校验单个 JSON 值是正整数 long，规则与 {@link #getPositiveLong} 相同；用于值已从对象中取出的场景。
     * @param value JSON 值，缺失时传 null
     * @param field 字段名，仅用于错误提示
     * @return 正整数值
     * @throws IllegalArgumentException 值格式错误或超出 long 范围
     */
    public static long confirmPositiveLong(JsonNode value, String field) {
        // 先限制格式，防止 JSON 或浏览器隐式转换丢失标识精度
        if (value == null || !(value.isIntegralNumber() || value.isTextual())
                || !value.asText().matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("JSON 字段不是有效正整数：" + field);
        }
        // 再确认调用者可使用 long 完整表示该值
        try {
            return Long.parseLong(value.asText());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("JSON 字段超出 long 范围：" + field, exception);
        }
    }

    /**
     * 按父字段名访问 JSON 对象，数组成员沿用数组字段名；访问者可修改对象，返回 false 则跳过其子树。
     * @param node 起始 JSON 值
     * @param context 起始节点的字段名，根节点可传空字符串
     * @param visitor 对象访问动作，返回是否继续访问子节点
     */
    public static void visitObjects(JsonNode node, String context, BiPredicate<String, ObjectNode> visitor) {
        if (node instanceof ObjectNode object) {
            if (!visitor.test(context, object)) {
                return;
            }
            // 访问者可能增删字段；按访问后的字段快照遍历，不持有会失效的迭代器
            var fields = new ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                visitObjects(object.get(field), field, visitor);
            }
        } else if (node.isArray()) {
            node.forEach(child -> visitObjects(child, context, visitor));
        }
    }
}
