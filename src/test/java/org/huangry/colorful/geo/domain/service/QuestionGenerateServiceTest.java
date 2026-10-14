package org.huangry.colorful.geo.domain.service;

import com.alibaba.fastjson.JSON;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisPlanService;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.*;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoProjectInfoEntity;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 检查制题边界：候选失败必须显式暴露，来源和语言由系统固定，生成不落库。 */
class QuestionGenerateServiceTest {
    private QuestionGenerateService service;
    private LLMClient client;
    private GeoDiagnosisPlanService plans;
    private GeoProjectInfoEntity project;

    @BeforeEach
    void setUp() {
        service = new QuestionGenerateService();
        client = mock(LLMClient.class);
        plans = spy(new GeoDiagnosisPlanService());
        ReflectionTestUtils.setField(service, "llmClient", client);

        ReflectionTestUtils.setField(service, "planService", plans);
        project = new GeoProjectInfoEntity();
        project.setName("WeKnora");
        project.setAliases(List.of("微诺拉"));
        project.setIndustry("企业知识库");
        project.setTargetAudience("开发者");
    }

    @Test
    void shouldReturnReviewedCandidatesWithoutSaving() {
        when(client.query(any(LLMQueryRequest.class))).thenReturn(LLMResponse.withoutCitations(
                "```json\n[{\"text\":\"企业知识库有哪些？\",\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}]\n```"));
        var candidates = service.generate(project, "generator", 1, "中文");
        assertEquals("GENERATED", candidates.get(0).getSource());
        assertEquals("中文", candidates.get(0).getLanguage());
        var request = org.mockito.ArgumentCaptor.forClass(LLMQueryRequest.class);
        verify(client).query(request.capture());
        assertEquals("generator", request.getValue().channelCode());
        assertTrue(request.getValue().prompt().contains("开发者"));
        verify(plans, never()).savePlan(any(), any(), any());
    }

    @Test
    void shouldRejectInvalidOrMisleadingModelOutput() {
        for (String response : List.of("说明而不是 JSON", "[]",
                "[{\"text\":{},\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}]",
                "[{\"text\":\"WeKnora 好用吗？\",\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}]",
                "[{\"text\":\"企业知识库推荐？\",\"intent\":\"INVALID\",\"brandScope\":\"NON_BRAND\"}]",
                "[{\"text\":\"企业知识库推荐？\",\"intent\":\"RECOMMENDATION\",\"brandScope\":\"NON_BRAND\"}] trailing")) {
            when(client.query(any(LLMQueryRequest.class))).thenReturn(LLMResponse.withoutCitations(response));
            assertThrows(LLMClientException.class, () -> service.generate(project, null, 1, "中文"));
        }
    }

    @Test
    void shouldRejectInvalidInputBeforeCallingModel() {
        assertThrows(IllegalArgumentException.class, () -> service.generate(project, null, 31, "中文"));
        assertThrows(IllegalArgumentException.class, () -> service.generate(project, null, 1, " "));
        verifyNoInteractions(client);
    }
}
