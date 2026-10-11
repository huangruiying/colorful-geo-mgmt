package org.huangry.colorful.geo.domain.service.llm;

import org.huangry.colorful.geo.domain.model.LlmChannel;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 渠道配置领域校验；错误输入必须在持久化前被拒绝。 */
class GeoLlmChannelServiceTest {
    /** HTTP 地址内的凭据、查询参数和脚本协议不可保存。 */
    @Test
    void shouldRejectCredentialUrlsAndInvalidReferences() {
        for (String url : new String[]{"https://key:secret@example.com/v1", "https://example.com?token=x", "javascript:alert(1)"}) {
            GeoLlmChannelService service = spy(new GeoLlmChannelService());
            LlmChannel c = channel(); c.setBaseUrl(url);
            assertThrows(IllegalArgumentException.class, () -> service.saveChannel(null, c, null));
            verify(service, never()).save(any());
        }

    }

    /** 超时及随机性越界拒绝，零优先级与负优先级仍可作为排序值。 */
    @Test
    void shouldRejectInvalidLimits() {
        LlmChannel c = channel(); c.setTemperature(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> new GeoLlmChannelService().saveChannel(null, c, null));
        c.setTemperature(0.3); c.setTimeoutSeconds(0);
        assertThrows(IllegalArgumentException.class, () -> new GeoLlmChannelService().saveChannel(null, c, null));
    }

    /** 编辑不可重新命名关联编码。 */
    @Test
    void shouldKeepChannelCodeStable() {
        GeoLlmChannelService service = spy(new GeoLlmChannelService());
        LlmChannel old = channel(); old.setChannelCode("original");
        doReturn(old).when(service).getChannel(1);
        assertThrows(IllegalArgumentException.class, () -> service.saveChannel(1L, channel(), null));
        verify(service, never()).updateById(any());
    }

    /** 模拟协议不能伪装成联网采集。 */
    @Test
    void shouldRejectSearchForMockChannel() {
        LlmChannel c = channel(); c.setProtocolType(LLMProviderType.MOCK); c.setWebSearchSupported(true);
        assertThrows(IllegalArgumentException.class, () -> new GeoLlmChannelService().saveChannel(null, c, null));
    }

    /** 构造有效远程连接，但不进行真实请求。 */
    private LlmChannel channel() {
        LlmChannel c = new LlmChannel(); c.setChannelCode("test"); c.setChannelName("测试");
        c.setProviderCode("test"); c.setProtocolType(LLMProviderType.OPENAI_COMPATIBLE);
        c.setBaseUrl("https://example.com/v1"); c.setModelName("test-model");
        return c;
    }
}
