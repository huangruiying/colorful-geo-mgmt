package org.huangry.colorful.geo.application.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.huangry.colorful.geo.application.llm.model.SaveLlmChannelRequest;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证实体仅在内部流转，全部对外结果隔离密钥并保持展示字段。 */
class LlmChannelBusinessTest {
    /** 保存、详情、列表和启停均返回无密钥的响应对象。 */
    @Test
    void shouldExcludeCredentialsFromEveryResponse() {
        var service = mock(GeoLlmChannelService.class);
        var business = new LlmChannelBusiness();
        ReflectionTestUtils.setField(business, "service", service);
        var entity = new GeoLlmChannelEntity();
        entity.setId(1L);
        entity.setApiKey("unit-test-placeholder");
        entity.setProtocolType(LLMProviderType.OPENAI_WEB_SEARCH);
        when(service.getById(1L)).thenReturn(entity);
        when(service.saveChannel(isNull(), any())).thenReturn(entity);
        when(service.listChannels(null, 20, 0)).thenReturn(List.of(entity));
        when(service.changeEnabled(1, true)).thenReturn(entity);
        var results = List.of(business.saveChannel(null, new SaveLlmChannelRequest()),
            business.getChannel(1), business.listChannels(null, 0, 20).items().get(0),
            business.changeEnabled(1, true));
        for (var response : results) {
            var json = new ObjectMapper().valueToTree(response);
            assertFalse(json.has("apiKey"));
            assertTrue(json.path("keyConfigured").asBoolean());
            assertTrue(json.path("webSearchSupported").asBoolean());
            assertEquals(1, json.path("id").asLong());
        }
        entity.setApiKey(" ");
        assertFalse(business.getChannel(1).getKeyConfigured());
        assertThrows(java.util.NoSuchElementException.class, () -> business.getChannel(99));
    }
}
