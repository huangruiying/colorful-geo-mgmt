package org.huangry.colorful.geo.domain.service.content;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.huangry.colorful.geo.application.content.model.ContentOptimizationRecordSummary;
import org.huangry.colorful.geo.domain.model.ContentOptimizationRecord;
import org.huangry.colorful.geo.infrastructure.repository.ContentOptimizationRecordDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentOptimizationRecordEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 内容优化记录持久化服务；通过 {@link ContentOptimizationRecordDao} 保存和查询记录，不调用大模型或平台。
 */
@Service
public class ContentOptimizationRecordService extends ServiceImpl<ContentOptimizationRecordDao, ContentOptimizationRecordEntity> {

    /**
     * 在一次写入中保存原文与优化稿，并返回带数据库标识的记录。
     *
     * @param record 已完成优化、等待入库的内容
     * @return 数据库保存后的内容记录
     */
    public ContentOptimizationRecord insert(ContentOptimizationRecord record) {
        // 1. 转为表映射对象，列表字段由 MyBatis-Plus 的 JSON 类型处理器负责。
        ContentOptimizationRecordEntity entity = buildContentOptimizationRecordEntity(record);

        // 2. 先完成数据库写入，失败时不返回未持久化的优化稿。
        if (!save(entity)) {
            throw new IllegalStateException("内容优化记录保存失败");
        }

        // 3. 读取数据库生成的 ID 和时间，返回可追溯的领域记录。
        return findById(entity.getId()).orElseThrow(() -> new IllegalStateException("内容优化记录保存后无法读取"));
    }

    /**
     * 按主键读取完整内容；未找到时由调用方决定业务语义。
     *
     * @param id 内容记录标识
     * @return 可选内容记录
     */
    public Optional<ContentOptimizationRecord> findById(long id) {
        return Optional.ofNullable(getById(id)).map(this::convertToContentOptimizationRecord);
    }

    /**
     * 查询页面摘要和各平台草稿数量，不加载长正文。
     *
     * @param size 每页条数
     * @param offset 起始偏移
     * @return 当前页内容摘要
     */
    public List<ContentOptimizationRecordSummary> listSummaries(int size, long offset) {
        return baseMapper.listSummaries(size, offset);
    }

    /**
     * 统计内容记录总数，供页面分页。
     *
     * @return 内容记录总数
     */
    public long countAll() {
        return count();
    }

    /**
     * 将领域内容转换为数据库字段，不复制平台任务状态。
     *
     * @param record 待保存的领域内容
     * @return 表映射对象
     */
    private ContentOptimizationRecordEntity buildContentOptimizationRecordEntity(ContentOptimizationRecord record) {
        ContentOptimizationRecordEntity entity = new ContentOptimizationRecordEntity();
        entity.setTitle(record.getTitle());
        entity.setOriginalContent(record.getOriginalContent());
        entity.setOptimizedContent(record.getOptimizedContent());
        entity.setOptimizationPreference(record.getOptimizationPreference());
        entity.setAppliedStrategies(record.getAppliedStrategies());
        entity.setWarnings(record.getWarnings());
        entity.setCitations(record.getCitations());
        return entity;
    }

    /**
     * 将数据库行还原为不含 ORM 注解的领域内容。
     *
     * @param entity 数据库查询结果
     * @return 内容领域对象
     */
    private ContentOptimizationRecord convertToContentOptimizationRecord(ContentOptimizationRecordEntity entity) {
        return ContentOptimizationRecord.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .originalContent(entity.getOriginalContent())
                .optimizedContent(entity.getOptimizedContent())
                .optimizationPreference(entity.getOptimizationPreference())
                .appliedStrategies(entity.getAppliedStrategies())
                .warnings(entity.getWarnings())
                .citations(entity.getCitations())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
