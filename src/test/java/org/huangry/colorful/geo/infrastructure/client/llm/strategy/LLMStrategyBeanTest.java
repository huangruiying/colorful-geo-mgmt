package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.repository.GeoLlmChannelDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证 Spring 自动注入 Bean 名到策略实例的 Map，不使用自建注册表。 */
class LLMStrategyBeanTest {
    /** 数据库 Bean 名直接命中实例，同协议厂商策略共存。 */
    @Test
    void shouldInjectActualBeanNamesAndRouteWithoutRegistry() {
        var dao = mock(GeoLlmChannelDao.class);
        var channel = new GeoLlmChannelEntity();
        channel.setChannelCode("qa");
        channel.setStrategyBeanName("mockLlmStrategy");
        channel.setSslVerify(true);
        when(dao.selectOne(any())).thenReturn(channel);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GeoLlmChannelDao.class, () -> dao);
            context.register(LLMStrategyRouter.class, MockLlmStrategy.class,
                    CompatibleChatCompletionsApiStrategy.class, CompatibleResponsesApiStrategy.class,
                    OpenAiChatCompletionsApiStrategy.class, OpenAiResponsesApiStrategy.class, DoubaoResponsesApiStrategy.class);
            context.refresh();
            var strategies = context.getBeansOfType(LLMStrategy.class);
            assertEquals(6, strategies.size());
            assertSame(context.getBean(DoubaoResponsesApiStrategy.class), strategies.get("doubaoResponsesApiStrategy"));
            assertFalse(strategies.containsKey("doubao_responses"));
            assertEquals(strategies.get("openAiResponsesApiStrategy").protocolType(),
                    strategies.get("doubaoResponsesApiStrategy").protocolType());
            assertTrue(context.getBean(LLMStrategyRouter.class).query(new LLMQueryRequest("问题")).content().contains("mock"));
        }
    }
}
