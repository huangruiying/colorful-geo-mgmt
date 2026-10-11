package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMConnectionConfig;
import org.huangry.colorful.geo.infrastructure.client.llm.model.*;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.GeoLlmChannelDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 渠道路由规则回归；验证筛选、确定性排序、协议匹配与无自动切换。 */
class LLMStrategyRouterTest {
    private final GeoLlmChannelDao dao = mock(GeoLlmChannelDao.class);

    /** 同一协议重复注册应在启动时失败，避免依赖组件加载顺序。 */
    @Test
    void shouldRejectDuplicateProtocolStrategies() {
        assertThrows(IllegalArgumentException.class,
            () -> createRouter(List.of(mockStrategy(), mockStrategy())));
    }

    /** 默认调用必须只选已启用配置，并采用固定优先级顺序。 */
    @Test
    void shouldSelectEnabledChannelsByScoreThenId() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "channel"), GeoLlmChannelEntity.class);
        LLMStrategy strategy = mockStrategy();
        when(dao.selectList(any())).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) invocation.getArgument(0);
            String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("enabled"));
            assertTrue(sql.contains("ORDER BY score DESC,id ASC"), sql);
            assertTrue(sql.contains("LIMIT 1"));
            return List.of(mockChannel());
        });
        assertEquals("模拟结果", createRouter(List.of(strategy)).query(new LLMQueryRequest("问题")).content());
    }

    /** 指定渠道和模型时，两项约束进入筛选，协议不影响默认渠道选择。 */
    @Test
    void shouldFilterExplicitChannelAndModel() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "channel"), GeoLlmChannelEntity.class);
        when(dao.selectList(any())).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) invocation.getArgument(0);
            String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("channelCode"));
            assertTrue(sql.contains("modelName"));
            assertFalse(sql.contains("protocolType"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("chosen"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("test-model"));
            return List.of();
        });
        var router = createRouter(List.of(mockStrategy()));
        assertThrows(LLMClientException.class, () -> router.query(new LLMQueryRequest(
            "问题", "chosen", "test-model")));
    }

    /** 模型约束可以独立使用，不强制指定渠道。 */
    @Test
    void shouldFilterModelWithoutChannel() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "channel"), GeoLlmChannelEntity.class);
        when(dao.selectList(any())).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) invocation.getArgument(0);
            String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("modelName")); assertFalse(sql.contains("channelCode"));
            return List.of(mockChannel());
        });
        createRouter(List.of(mockStrategy())).query(
            new LLMQueryRequest("问题", null, "test-model"));
    }

    /** 没有匹配渠道时不使用 YAML 默认渠道或模拟回答兜底。 */
    @Test
    void shouldRejectUnavailableChannel() {
        when(dao.selectList(any())).thenReturn(List.of());
        LLMStrategy strategy = mockStrategy();
        assertThrows(LLMClientException.class, () -> createRouter(List.of(strategy))
            .query(new LLMQueryRequest("问题")));
        verify(strategy, never()).query(any(), any(), any());
    }

    /** 远程策略异常直接向上返回，不能改用第二个模型。 */
    @Test
    void shouldNotRetryWithAnotherChannel() {
        LLMStrategy strategy = mockStrategy();
        when(dao.selectList(any())).thenReturn(List.of(mockChannel()));
        when(strategy.query(any(), any(), any())).thenThrow(new IllegalStateException("远程失败"));
        assertThrows(IllegalStateException.class, () -> createRouter(List.of(strategy))
            .query(new LLMQueryRequest("问题")));
        verify(dao, times(1)).selectList(any());
        verify(strategy, times(1)).query(any(), any(), any());
    }

    /** 模拟 Spring 字段注入后执行初始化，验证注册生命周期。 */
    private LLMStrategyRouter createRouter(List<LLMStrategy> candidates) {
        var router = new LLMStrategyRouter();
        ReflectionTestUtils.setField(router, "channelDao", dao);
        ReflectionTestUtils.setField(router, "candidates", candidates);
        router.registerStrategies();
        return router;
    }

    /** 构造无凭据的模拟渠道，测试不触发远程请求。 */
    private GeoLlmChannelEntity mockChannel() {
        GeoLlmChannelEntity c = new GeoLlmChannelEntity();
        c.setChannelCode("mock"); c.setProtocolType(LLMProviderType.MOCK); c.setTemperature(0.3);
        c.setMaxTokens(1400); c.setTimeoutSeconds(60); c.setSslVerify(true);
        return c;
    }

    /** 模拟协议策略，仅验证路由边界。 */
    private LLMStrategy mockStrategy() {
        LLMStrategy s = mock(LLMStrategy.class);
        when(s.llmProviderType()).thenReturn(LLMProviderType.MOCK);
        when(s.query(any(), any(), any())).thenReturn(LLMResponse.withoutCitations("模拟结果"));
        return s;
    }
}
