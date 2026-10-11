package org.huangry.colorful.geo.infrastructure.client.llm.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 单次采样输入校验：无效问题拒绝，有效问题保留原文。 */
class LLMQueryRequestTest {
    /** 不清理或改写用户问题，保证重复调用使用相同输入。 */
    @Test
    void shouldRejectBlankPromptAndPreserveOriginalText() {
        for (String prompt : new String[]{null, "", " \n "}) {
            assertThrows(IllegalArgumentException.class, () -> new LLMQueryRequest(prompt));
        }
        assertEquals(" 问题\n", new LLMQueryRequest(" 问题\n").prompt());
    }
}
