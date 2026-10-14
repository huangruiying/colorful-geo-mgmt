package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.LLMStrategy;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.*;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.GeoLlmChannelDao;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 渠道路由规则回归；验证筛选、确定性排序、协议匹配与无自动切换。 */
class LLMStrategyRouterTest {
    /** 任务指定的实体直接交给策略，不再查询优先级渠道。 */
    @Test
    void shouldUseExactTaskChannelWithoutSelectingFallback() {
        var channel = mockChannel(); channel.setId(99L); channel.setEnabled(true);
        var strategy = mockStrategy();
        var request = new LLMQueryRequest("独立问题");
        assertEquals("模拟结果", createRouter(Map.of("mockLlmStrategy", strategy)).query(request, channel).getContent());
        verify(strategy).query(same(request), same(channel));
        verifyNoInteractions(dao);
    }

    /** 精确任务入口不能使用停用渠道，即使 Bean 已注册也不调用。 */
    @Test
    void shouldRejectDisabledExactTaskChannel() {
        var channel = mockChannel(); channel.setEnabled(false);
        var strategy = mockStrategy();
        assertThrows(LLMClientException.class, () -> createRouter(Map.of("mockLlmStrategy", strategy))
                .query(new LLMQueryRequest("问题"), channel));
        verify(strategy, never()).query(any(), any());
        verifyNoInteractions(dao);
    }
    /** 远程渠道缺少密钥时，在请求前拒绝，不影响 Mock 无密钥调用。 */
    @Test
    void shouldRejectBlankRemoteKeyBeforeCallingStrategy() {
        var strategy = mockStrategy();
        when(strategy.protocolType()).thenReturn(LLMProtocolType.OPENAI_RESPONSES);
        for (String key : new String[]{null, "", "  "}) {
            var channel = mockChannel();
            channel.setApiKey(key);
            when(dao.selectOne(any())).thenReturn(channel);
            assertThrows(LLMClientException.class, () -> createRouter(Map.of("mockLlmStrategy", strategy)).query(new LLMQueryRequest("问题")));
        }
        verify(strategy, never()).query(any(), any());
    }
    /** 数据库编码选择厂商策略，相同协议不能被当作同一个策略。 */
    @Test
    void shouldRouteByStoredStrategyBeanName() {
        LLMStrategy openai = mockStrategy();
        LLMStrategy doubao = mockStrategy();
                        when(openai.protocolType()).thenReturn(LLMProtocolType.OPENAI_RESPONSES);
        when(doubao.protocolType()).thenReturn(LLMProtocolType.OPENAI_RESPONSES);
        var channel = mockChannel();
        channel.setStrategyBeanName("responsesApiDoubaoStrategy");
        channel.setApiKey("unit-test-key");
        when(dao.selectOne(any())).thenReturn(channel);
        createRouter(Map.of("responsesApiOpenAiStrategy", openai, "responsesApiDoubaoStrategy", doubao)).query(new LLMQueryRequest("问题"));
        verify(doubao).query(any(), same(channel));
        verify(openai, never()).query(any(), any());
    }

    /** 无效编码在调用前拒绝，不退回相同协议的其他策略。 */
    @Test
    void shouldRejectUnknownStoredStrategy() {
        var channel = mockChannel();
        channel.setStrategyBeanName("missing");
        when(dao.selectOne(any())).thenReturn(channel);
        var strategy = mockStrategy();
        assertThrows(LLMClientException.class, () -> createRouter(Map.of("mockLlmStrategy", strategy)).query(new LLMQueryRequest("问题")));
        verify(strategy, never()).query(any(), any());
    }
    private final GeoLlmChannelDao dao = mock(GeoLlmChannelDao.class);

    /** 默认调用必须只选已启用配置，并采用固定优先级顺序。 */
    @Test
    void shouldSelectEnabledChannelsByScoreThenId() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "channel"), GeoLlmChannelEntity.class);
        LLMStrategy strategy = mockStrategy();
        when(dao.selectOne(any())).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) invocation.getArgument(0);
            String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("enabled"));
            assertTrue(sql.contains("ORDER BY score DESC,id ASC"), sql);
            assertTrue(sql.contains("LIMIT 1"));
            return mockChannel();
        });
        assertEquals("模拟结果", createRouter(Map.of("mockLlmStrategy", strategy)).query(new LLMQueryRequest("问题")).getContent());
    }

    /** 指定渠道和模型时，两项约束进入筛选，协议不影响默认渠道选择。 */
    @Test
    void shouldFilterExplicitChannelAndModel() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "channel"), GeoLlmChannelEntity.class);
        when(dao.selectOne(any())).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) invocation.getArgument(0);
            String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("channelCode"));
            assertTrue(sql.contains("modelName"));
            assertFalse(sql.contains("protocolType"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("chosen"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("test-model"));
            return null;
        });
        var router = createRouter(Map.of("mockLlmStrategy", mockStrategy()));
        assertThrows(LLMClientException.class, () -> router.query(new LLMQueryRequest(
            "问题", "chosen", "test-model")));
    }

    /** 模型约束可以独立使用，不强制指定渠道。 */
    @Test
    void shouldFilterModelWithoutChannel() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "channel"), GeoLlmChannelEntity.class);
        when(dao.selectOne(any())).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) invocation.getArgument(0);
            String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("modelName")); assertFalse(sql.contains("channelCode"));
            return mockChannel();
        });
        createRouter(Map.of("mockLlmStrategy", mockStrategy())).query(
            new LLMQueryRequest("问题", null, "test-model"));
    }

    /** 没有匹配渠道时不使用 YAML 默认渠道或模拟回答兜底。 */
    @Test
    void shouldRejectUnavailableChannel() {
        when(dao.selectOne(any())).thenReturn(null);
        LLMStrategy strategy = mockStrategy();
        assertThrows(LLMClientException.class, () -> createRouter(Map.of("mockLlmStrategy", strategy))
            .query(new LLMQueryRequest("问题")));
        verify(strategy, never()).query(any(), any());
    }

    /** 远程策略异常直接向上返回，不能改用第二个模型。 */
    @Test
    void shouldNotRetryWithAnotherChannel() {
        LLMStrategy strategy = mockStrategy();
        when(dao.selectOne(any())).thenReturn(mockChannel());
        when(strategy.query(any(), any())).thenThrow(new IllegalStateException("远程失败"));
        assertThrows(IllegalStateException.class, () -> createRouter(Map.of("mockLlmStrategy", strategy))
            .query(new LLMQueryRequest("问题")));
        verify(dao, times(1)).selectOne(any());
        verify(strategy, times(1)).query(any(), any());
    }

    /** 模拟 Spring 字段注入的实际 Bean Map，不手工注册编码。 */
    private LLMStrategyRouter createRouter(Map<String, LLMStrategy> strategies) {
        var router = new LLMStrategyRouter();
        ReflectionTestUtils.setField(router, "channelDao", dao);
        ReflectionTestUtils.setField(router, "strategies", strategies);
        return router;
    }

    /** 构造无凭据的模拟渠道，测试不触发远程请求。 */
    private GeoLlmChannelEntity mockChannel() {
        GeoLlmChannelEntity c = new GeoLlmChannelEntity();
        c.setChannelCode("mock"); c.setStrategyBeanName("mockLlmStrategy"); c.setTemperature(0.3);
        c.setMaxTokens(1400); c.setTimeoutSeconds(60); c.setSslVerify(true);
        return c;
    }

    /** 模拟协议策略，仅验证路由边界。 */
    private LLMStrategy mockStrategy() {
        LLMStrategy s = mock(LLMStrategy.class);
        when(s.protocolType()).thenReturn(LLMProtocolType.MOCK);
        when(s.query(any(), any())).thenReturn(LLMResponse.withoutCitations("模拟结果"));
        return s;
    }
}
