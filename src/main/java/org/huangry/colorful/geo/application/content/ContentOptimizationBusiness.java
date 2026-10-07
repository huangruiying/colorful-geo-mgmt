package org.huangry.colorful.geo.application.content;

import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.application.content.model.ContentOptimizationRecordDetail;
import org.huangry.colorful.geo.application.content.model.ContentOptimizationRecordPage;
import org.huangry.colorful.geo.application.content.model.OptimizeContentCommand;
import org.huangry.colorful.geo.domain.model.ContentOptimizationRequest;
import org.huangry.colorful.geo.domain.model.ContentOptimizationResult;
import org.huangry.colorful.geo.domain.model.ContentOptimizationRecord;
import org.huangry.colorful.geo.domain.service.content.ContentPublishRecordService;
import org.huangry.colorful.geo.domain.service.content.ContentOptimizationRecordService;
import org.huangry.colorful.geo.domain.service.optimization.ContentOptimizationService;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * 内容优化业务入口：负责生成并保存优化稿、查询优化记录；不发起平台发布。
 */
@Service
@RequiredArgsConstructor
public class ContentOptimizationBusiness {

    private final ContentOptimizationService optimizationService;
    private final ContentOptimizationRecordService optimizationRecordService;
    private final ContentPublishRecordService publishRecordService;

    /**
     * 优化正文并将原文与模型结果一同入库，返回可追溯的内容记录。
     *
     * @param command 标题、原文和可选优化参数
     * @return 已保存的内容优化记录
     */
    public ContentOptimizationRecord optimizeAndSave(OptimizeContentCommand command) {
        // 1. 标题只属于内容记录，正文及策略边界继续由现有领域优化服务校验。
        if (command == null || command.title() == null || command.title().isBlank()
                || command.title().length() > 200) {
            throw new IllegalArgumentException("标题不能为空且不能超过 200 字符");
        }
        if (command.optimizationPreference() != null && command.optimizationPreference().length() > 1000) {
            throw new IllegalArgumentException("优化偏好不能超过 1000 字符");
        }
        ContentOptimizationRequest request = new ContentOptimizationRequest(command.content(),
                command.optimizationPreference(), command.strategies(), command.references());

        // 2. 优化成功才写入记录；原文、优化稿、策略、提示和引用在同一次写入中保存。
        ContentOptimizationResult result = optimizationService.optimizeContent(request);
        ContentOptimizationRecord record = ContentOptimizationRecord.builder()
                .title(command.title().trim())
                .originalContent(command.content())
                .optimizedContent(result.optimizedContent())
                .optimizationPreference(command.optimizationPreference())
                .appliedStrategies(result.appliedStrategies())
                .warnings(result.warnings())
                .citations(result.citations())
                .build();
        return optimizationRecordService.insert(record);
    }

    /**
     * 读取内容摘要分页，避免列表请求加载每篇长正文。
     *
     * @param page 从零开始的页码
     * @param size 每页条数，上限 100
     * @return 当前页及总数
     */
    public ContentOptimizationRecordPage listContentOptimizationRecords(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("分页参数不合法：page 不小于 0，size 范围为 1～100");
        }
        long offset = (long) page * size;
        return new ContentOptimizationRecordPage(optimizationRecordService.listSummaries(size, offset),
                optimizationRecordService.countAll(), page, size);
    }

    /**
     * 读取一篇优化内容及其所有平台发布记录，供确认稿件和查看草稿结果。
     *
     * @param id 内容记录 ID
     * @return 优化内容及平台发布记录详情
     */
    public ContentOptimizationRecordDetail getContentOptimizationRecord(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("内容记录 ID 必须大于零");
        }
        ContentOptimizationRecord record = optimizationRecordService.findById(id)
                .orElseThrow(() -> new NoSuchElementException("内容记录不存在"));
        return new ContentOptimizationRecordDetail(record, publishRecordService.listByContentOptimizationRecordId(id));
    }

}
