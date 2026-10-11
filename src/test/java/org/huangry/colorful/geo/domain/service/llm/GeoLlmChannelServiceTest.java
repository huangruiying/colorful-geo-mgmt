package org.huangry.colorful.geo.domain.service.llm;

import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 渠道配置领域校验；错误输入必须在持久化前被拒绝。 */
class GeoLlmChannelServiceTest {
    /** 空密钥不进入更新，新密钥清理空白后保存；输入系统字段不可覆盖。 */
    @Test
    void shouldPreserveExistingKeyOrSaveReplacement() {
        for (String input : new String[]{null, "  ", " new-test-key "}) {
            GeoLlmChannelService service = spy(new GeoLlmChannelService());
            var existing = channel();
            existing.setApiKey("existing-test-key");
            doReturn(existing).when(service).getChannel(1);
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
            verify(service, times(2)).getChannel(1);
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
        doReturn(old).when(service).getChannel(1);
        assertThrows(IllegalArgumentException.class, () -> service.saveChannel(1L, channel()));
        verify(service, never()).updateById(any());
    }

    /** 搜索能力始终由协议枚举推导。 */
    @Test
    void shouldRejectSearchForMockChannel() {
        GeoLlmChannelEntity c = channel(); c.setProtocolType(LLMProviderType.MOCK);
        assertFalse(c.getProtocolType().isWebSearchSupported());
        c.setProtocolType(LLMProviderType.OPENAI_RESPONSES);
        assertTrue(c.getProtocolType().isWebSearchSupported());
    }

    /** 构造有效远程连接，但不进行真实请求。 */
    private GeoLlmChannelEntity channel() {
        GeoLlmChannelEntity c = new GeoLlmChannelEntity(); c.setChannelCode("test"); c.setChannelName("测试");
        c.setProviderCode("test"); c.setProtocolType(LLMProviderType.OPENAI_COMPATIBLE);
        c.setBaseUrl("https://example.com/v1"); c.setModelName("test-model");
        return c;
    }
}
