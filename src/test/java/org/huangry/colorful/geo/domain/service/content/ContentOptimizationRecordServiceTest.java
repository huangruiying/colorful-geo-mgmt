package org.huangry.colorful.geo.domain.service.content;

import org.huangry.colorful.geo.domain.model.ContentOptimizationRecord;
import org.huangry.colorful.geo.infrastructure.common.enums.ContentOptimizationStrategy;
import org.huangry.colorful.geo.infrastructure.repository.ContentOptimizationRecordDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentOptimizationRecordEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内容优化持久化服务测试；只模拟 DAO，验证输入与输出的映射。
 */
class ContentOptimizationRecordServiceTest {

    private ContentOptimizationRecordDao dao;
    private ContentOptimizationRecordService service;

    /** 为每个测试建立独立的 DAO 替身，不访问本地 MySQL。 */
    @BeforeEach
    void setUp() {
        dao = mock(ContentOptimizationRecordDao.class);
        service = new ContentOptimizationRecordService();
        ReflectionTestUtils.setField(service, "baseMapper", dao);
    }

    /** 优化记录写入后应返回实际 ID，且 JSON 列表保留原有领域类型。 */
    @Test
    void 保存优化结果后返回数据库记录() {
        when(dao.insert(any(ContentOptimizationRecordEntity.class))).thenAnswer(invocation -> {
            ContentOptimizationRecordEntity entity = invocation.getArgument(0);
            entity.setId(7L);
            return 1;
        });
        when(dao.selectById(7L)).thenAnswer(invocation -> {
            ContentOptimizationRecordEntity entity = new ContentOptimizationRecordEntity();
            entity.setId(7L);
            entity.setTitle("标题");
            entity.setOriginalContent("原文");
            entity.setOptimizedContent("优化稿");
            entity.setAppliedStrategies(List.of(ContentOptimizationStrategy.EASY_TO_UNDERSTAND));
            entity.setWarnings(List.of("核验事实"));
            entity.setCitations(List.of());
            return entity;
        });

        ContentOptimizationRecord saved = service.insert(ContentOptimizationRecord.builder()
                .title("标题").originalContent("原文").optimizedContent("优化稿")
                .appliedStrategies(List.of(ContentOptimizationStrategy.EASY_TO_UNDERSTAND))
                .warnings(List.of("核验事实")).citations(List.of())
                .build());

        assertEquals(7L, saved.getId());
        assertEquals(List.of(ContentOptimizationStrategy.EASY_TO_UNDERSTAND), saved.getAppliedStrategies());
        assertEquals(List.of("核验事实"), saved.getWarnings());
    }

}
