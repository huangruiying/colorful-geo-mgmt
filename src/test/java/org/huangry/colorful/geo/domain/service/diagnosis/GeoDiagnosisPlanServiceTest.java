package org.huangry.colorful.geo.domain.service.diagnosis;

import org.huangry.colorful.geo.infrastructure.repository.*;
import org.huangry.colorful.geo.infrastructure.repository.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 问题及关联规则测试；事务原子性另用本地 MySQL 验证。 */
class GeoDiagnosisPlanServiceTest {
    private final GeoDiagnosisPlanService service = new GeoDiagnosisPlanService();

    @Test
    void shouldRejectBrandNamesAndAliasesInNonBrandQuestions() {
        for (String text : List.of("ＷｅＫｎｏｒａ怎么部署", "w e k n o r a好用吗", "微诺拉推荐吗")) {
            assertThrows(IllegalArgumentException.class, () -> service.validateQuestions(project(), List.of(question(text))));
        }
        var branded = question("WeKnora怎么部署");
        branded.setBrandScope("BRAND");
        assertDoesNotThrow(() -> service.validateQuestions(project(), List.of(branded)));
    }

    @Test
    void shouldRejectDuplicatesAndNormalizeOrder() {
        assertThrows(IllegalArgumentException.class, () -> service.validateQuestions(project(),
                List.of(question("RAG 有哪些？"), question("rag有哪些？"))));
        var first = question(" 企业知识库如何选型？ ");
        var second = question("开源知识库有哪些？");
        service.validateQuestions(project(), List.of(first, second));
        assertEquals("企业知识库如何选型？", first.getQuestionText());
        assertEquals(0, first.getSortOrder());
        assertEquals(1, second.getSortOrder());
    }

    @Test
    void shouldRejectDisabledChannelsBeforeDatabaseWrite() {
        var projectDao = mock(GeoProjectInfoDao.class);
        var channelDao = mock(GeoLlmChannelDao.class);
        var planDao = mock(GeoDiagnosisPlanDao.class);
        ReflectionTestUtils.setField(service, "projectDao", projectDao);
        ReflectionTestUtils.setField(service, "llmChannelDao", channelDao);
        ReflectionTestUtils.setField(service, "baseMapper", planDao);
        when(projectDao.selectById(1L)).thenReturn(project());
        var disabled = new GeoLlmChannelEntity(); disabled.setEnabled(false);
        when(channelDao.selectById(2L)).thenReturn(disabled);
        var plan = new GeoDiagnosisPlanEntity();
        plan.setName("基线"); plan.setProjectId(1L); plan.setRepeatCount(3);
        var channel = new GeoDiagnosisPlanChannelEntity(); channel.setChannelId(2L);
        assertThrows(IllegalArgumentException.class, () -> service.savePlan(plan,
                List.of(question("企业知识库有哪些？")), List.of(channel)));
        verifyNoInteractions(planDao);
    }

    private GeoProjectInfoEntity project() {
        var project = new GeoProjectInfoEntity();
        project.setName("WeKnora"); project.setAliases(List.of("微诺拉")); return project;
    }

    private GeoDiagnosisPlanQuestionEntity question(String text) {
        var question = new GeoDiagnosisPlanQuestionEntity();
        question.setQuestionText(text); question.setSource("MANUAL"); question.setIntent("RECOMMENDATION");
        question.setBrandScope("NON_BRAND"); question.setLanguage("中文"); return question;
    }
}
