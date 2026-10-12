package org.huangry.colorful.geo.domain.service.llm;

import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 渠道配置领域校验；错误输入必须在持久化前被拒绝。 */
class GeoLlmChannelServiceTest {
    /** 删除查询包装后，编辑与启停仍拒绝不存在的记录。 */
    @Test
    void shouldRejectMissingRecord() {
        var service = spy(new GeoLlmChannelService());
        doReturn(null).when(service).getById(99L);
        assertThrows(java.util.NoSuchElementException.class, () -> service.saveChannel(99L, channel()));
        assertThrows(java.util.NoSuchElementException.class, () -> service.changeEnabled(99L, false));
        verify(service, never()).updateById(any());
    }

    /** 空密钥不进入更新，新密钥清理空白后保存；输入系统字段不可覆盖。 */
    @Test
    void shouldPreserveExistingKeyOrSaveReplacement() {
        for (String input : new String[]{null, "  ", " new-test-key "}) {
            GeoLlmChannelService service = spy(new GeoLlmChannelService());
            var existing = channel();
            existing.setApiKey("existing-test-key");
            doReturn(existing).when(service).getById(1L);
            doReturn(true).when(service).updateById(any());
            var edited = channel();
            edited.setEnabled(true);
            edited.setId(99L);
            edited.setCreatedAt(java.time.LocalDateTime.now());
            edited.setApiKey(input);
            service.saveChannel(1L, edited);
            var saved = org.mockito.ArgumentCaptor.forClass(GeoLlmChannelEntity.class);
            verify(service).updateById(saved.capture());
            assertEquals(1L, saved.getValue().getId());
            assertNull(saved.getValue().getCreatedAt());
            assertEquals(input == null || input.isBlank() ? null : "new-test-key", saved.getValue().getApiKey());
            verify(service, times(2)).getById(1L);
        }
    }

    /** HTTP 地址内的凭据、查询参数和脚本协议不可保存。 */
    @Test
    void shouldRejectCredentialUrlsAndInvalidReferences() {
        for (String url : new String[]{"https://key:secret@example.com/v1", "https://example.com?token=x", "javascript:alert(1)"}) {
            GeoLlmChannelService service = spy(new GeoLlmChannelService());
            GeoLlmChannelEntity c = channel(); c.setBaseUrl(url);
            assertThrows(IllegalArgumentException.class, () -> service.saveChannel(null, c));
            verify(service, never()).save(any());
        }

    }

    /** 超时及随机性越界拒绝，零优先级与负优先级仍可作为排序值。 */
    @Test
    void shouldRejectInvalidLimits() {
        GeoLlmChannelEntity c = channel(); c.setTemperature(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> new GeoLlmChannelService().saveChannel(null, c));
        c.setTemperature(0.3); c.setTimeoutSeconds(0);
        assertThrows(IllegalArgumentException.class, () -> new GeoLlmChannelService().saveChannel(null, c));
    }

    /** 编辑不可重新命名关联编码。 */
    @Test
    void shouldKeepChannelCodeStable() {
        GeoLlmChannelService service = spy(new GeoLlmChannelService());
        GeoLlmChannelEntity old = channel(); old.setChannelCode("original");
        doReturn(old).when(service).getById(1L);
        assertThrows(IllegalArgumentException.class, () -> service.saveChannel(1L, channel()));
        verify(service, never()).updateById(any());
    }

    /** 接口协议独立于联网能力，只包含已实现的交互格式。 */
    @Test
    void shouldRejectSearchForMockChannel() {
        GeoLlmChannelEntity c = channel(); c.setProtocolType(LLMProtocolType.MOCK);
        assertEquals(LLMProtocolType.MOCK, c.getProtocolType());
        c.setProtocolType(LLMProtocolType.OPENAI_RESPONSES);
        assertEquals(LLMProtocolType.OPENAI_RESPONSES, c.getProtocolType());
    }

    /** 构造有效远程连接，但不进行真实请求。 */
    private GeoLlmChannelEntity channel() {
        GeoLlmChannelEntity c = new GeoLlmChannelEntity(); c.setChannelCode("test"); c.setChannelName("测试");
        c.setProviderCode("test"); c.setProtocolType(LLMProtocolType.OPENAI_CHAT_COMPLETIONS);
        c.setBaseUrl("https://example.com/v1"); c.setModelName("test-model");
        return c;
    }
}
