package org.huangry.colorful.geo.application.content;

import org.huangry.colorful.geo.application.content.model.OptimizeContentCommand;
import org.huangry.colorful.geo.domain.model.ContentOptimizationRecord;
import org.huangry.colorful.geo.domain.model.ContentOptimizationResult;
import org.huangry.colorful.geo.domain.service.content.ContentOptimizationRecordService;
import org.huangry.colorful.geo.domain.service.content.ContentPublishRecordService;
import org.huangry.colorful.geo.domain.service.optimization.ContentOptimizationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容优化业务测试；模型与持久化服务使用替身，不发起真实优化请求。
 */
class ContentOptimizationBusinessTest {

    /** 模型优化成功后，原文与优化稿应作为同一记录保存。 */
    @Test
    void 应保存原文和优化结果() {
        ContentOptimizationService optimizationService = mock(ContentOptimizationService.class);
        ContentOptimizationRecordService optimizationRecordService = mock(ContentOptimizationRecordService.class);
        ContentPublishRecordService publishRecordService = mock(ContentPublishRecordService.class);
        ContentOptimizationBusiness service = new ContentOptimizationBusiness(
                optimizationService, optimizationRecordService, publishRecordService);
        when(optimizationService.optimizeContent(any())).thenReturn(
                new ContentOptimizationResult("优化稿", List.of(), List.of("需核验"), List.of()));
        when(optimizationRecordService.insert(any())).thenAnswer(invocation ->
                ((ContentOptimizationRecord) invocation.getArgument(0)).toBuilder().id(7L).build());

        ContentOptimizationRecord saved = service.optimizeAndSave(
                new OptimizeContentCommand("标题", "原文", null, List.of(), List.of()));

        assertEquals(7L, saved.getId());
        assertEquals("原文", saved.getOriginalContent());
        assertEquals("优化稿", saved.getOptimizedContent());
        verify(optimizationRecordService).insert(any());
    }
}
