package org.huangry.colorful.geo.infrastructure.client.llm.output;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;

import java.util.List;
import java.util.Set;

/**
 * 解析大模型返回的 JSON 正文的通用方法：截出 JSON 对象、取对象数组、校验取值；不是 JSON 时抛出异常，由调用方按失败处理。
 * <p>只做格式层面的解析，与具体业务无关；字段含义的校验由调用方负责。</p>
 */
public final class LLMJsonOutputParser {
    private LLMJsonOutputParser() {
    }

    /**
     * 从模型正文中截出最外层 JSON 对象；模型常在前后附带说明或 Markdown 代码块，只取第一个 { 到最后一个 } 之间的内容。
     * @param output 模型正文
     * @param outputName 输出名称，拼在出错提示前面，如“判定结果”
     * @return 最外层 JSON 对象
     * @throws IllegalArgumentException 正文为空、找不到或无法解析 JSON 对象
     */
    public static JSONObject extractObject(String output, String outputName) {
        if (output == null) {
            throw new IllegalArgumentException(outputName + "为空");
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException(outputName + "不是 JSON 对象");
        }
        try {
            return JSON.parseObject(output.substring(start, end + 1));
        } catch (JSONException exception) {
            throw new IllegalArgumentException(outputName + "不是有效 JSON", exception);
        }
    }

    /**
     * 取 JSON 数组字段中的对象元素，忽略字符串、数字等其他元素。
     * @param value JSON 数组字段
     * @return 对象元素；字段不是数组时为空列表
     */
    public static List<JSONObject> listObjects(Object value) {
        if (!(value instanceof JSONArray array)) {
            return List.of();
        }
        return array.stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast).toList();
    }

    /**
     * 判断模型给出的取值是否在约定范围内；模型可能省略字段，null 视为不在范围内（Set.of 的 contains 不接受 null）。
     * @param values 约定取值
     * @param value 模型给出的取值，可为 null
     * @return 非 null 且在约定范围内时为 true
     */
    public static boolean isAllowed(Set<String> values, String value) {
        return value != null && values.contains(value);
    }
}
