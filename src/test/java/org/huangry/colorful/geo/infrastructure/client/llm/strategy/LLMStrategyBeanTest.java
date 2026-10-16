package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.LLMStrategy;
import org.huangry.colorful.geo.infrastructure.repository.GeoLlmChannelDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "http.llm.timeout-seconds=60", "http.llm.ssl-verify=true");
            context.registerBean(GeoLlmChannelDao.class, () -> dao);
            context.register(org.huangry.colorful.geo.infrastructure.config.RestTemplateConfig.class,
                    LLMStrategyRouter.class, MockLlmStrategy.class,
                    ChatCompletionsApiCommonStrategy.class, ResponsesApiCommonStrategy.class,
                    ChatCompletionsApiOpenAiStrategy.class, ResponsesApiOpenAiStrategy.class, ResponsesApiDoubaoStrategy.class,
                    ResponsesApiDeepSeekStrategy.class, ResponsesApiQwenStrategy.class,
                    ResponsesApiZhipuStrategy.class, ChatCompletionsApiQianfanStrategy.class);
            context.refresh();
            var strategies = context.getBeansOfType(LLMStrategy.class);
            assertEquals(10, strategies.size());
            assertSame(context.getBean(ChatCompletionsApiCommonStrategy.class), strategies.get("chatCompletionsApiCommonStrategy"));
            assertSame(context.getBean(ResponsesApiCommonStrategy.class), strategies.get("responsesApiCommonStrategy"));
            assertFalse(strategies.containsKey("compatibleChatCompletionsApiStrategy"));
            assertFalse(strategies.containsKey("compatibleResponsesApiStrategy"));
            assertSame(context.getBean(ResponsesApiDoubaoStrategy.class), strategies.get("responsesApiDoubaoStrategy"));
            assertSame(context.getBean(ResponsesApiDeepSeekStrategy.class), strategies.get("responsesApiDeepSeekStrategy"));
            assertSame(context.getBean(ResponsesApiQwenStrategy.class), strategies.get("responsesApiQwenStrategy"));
            assertSame(context.getBean(ResponsesApiZhipuStrategy.class), strategies.get("responsesApiZhipuStrategy"));
            assertSame(context.getBean(ChatCompletionsApiQianfanStrategy.class), strategies.get("chatCompletionsApiQianfanStrategy"));
            assertSame(context.getBean(ChatCompletionsApiOpenAiStrategy.class), strategies.get("chatCompletionsApiOpenAiStrategy"));
            assertSame(context.getBean(ResponsesApiOpenAiStrategy.class), strategies.get("responsesApiOpenAiStrategy"));
            assertFalse(strategies.containsKey("doubao_responses"));
            assertEquals(strategies.get("responsesApiOpenAiStrategy").protocolType(),
                    strategies.get("responsesApiDoubaoStrategy").protocolType());
            assertTrue(context.getBean(LLMStrategyRouter.class).query(new LLMQueryRequest("问题")).getContent().contains("mock"));
        }
    }
}
