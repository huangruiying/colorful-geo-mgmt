package org.huangry.colorful.geo.application.content;

import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.application.content.model.ContentPublishRecordPage;
import org.huangry.colorful.geo.domain.model.ContentPublishRecord;
import org.huangry.colorful.geo.domain.service.content.ContentOptimizationRecordService;
import org.huangry.colorful.geo.domain.service.content.ContentPublishRecordService;
import org.huangry.colorful.geo.domain.service.publish.ContentPublicationService;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 内容发布业务入口：负责预发布与本地发布记录管理；不生成优化稿，也不删除平台内容。
 */
@Service
@RequiredArgsConstructor
public class ContentPublishBusiness {

    private final ContentPublicationService publicationService;
    private final ContentOptimizationRecordService optimizationRecordService;
    private final ContentPublishRecordService publishRecordService;

    /**
     * 分页读取逐平台发布记录，供独立发布列表查看状态和平台结果。
     *
     * @param page 从零开始的页码
     * @param size 每页条数，上限 100
     * @return 发布记录分页
     */
    public ContentPublishRecordPage listContentPublishRecords(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("分页参数不合法：page 不小于 0，size 范围为 1～100");
        }
        long offset = (long) page * size;
        return new ContentPublishRecordPage(publishRecordService.listContentPublishRecords(size, offset),
                publishRecordService.countContentPublishRecords(), page, size);
    }

    /**
     * 只删除本地发布记录；平台已有草稿或文章仍需到对应平台管理。
     *
     * @param id 发布记录 ID
     */
    public void deleteContentPublishRecord(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("发布记录 ID 必须大于零");
        }
        publishRecordService.deleteContentPublishRecord(id);
    }

    /**
     * 用户在弹窗确认标题、正文和平台后，为每个平台建立发布记录并创建草稿。
     *
     * @param id 内容记录 ID
     * @param publicationTitle 用户确认的发布标题
     * @param publicationContent 用户确认的发布正文
     * @param platformTypes 本次选择的平台
     * @return 当前内容的全部平台发布记录
     */
    public List<ContentPublishRecord> prePublish(long id, String publicationTitle,
                                                 String publicationContent,
                                                 List<PublicationPlatformType> platformTypes) {
        // 1. 请求中的标题和正文就是本次人工确认稿，不修改优化记录。
        if (id <= 0) {
            throw new IllegalArgumentException("内容记录 ID 必须大于零");
        }
        if (publicationTitle == null || publicationTitle.isBlank() || publicationTitle.length() > 200
                || publicationContent == null || publicationContent.isBlank()) {
            throw new IllegalArgumentException("最终稿标题不能为空且不能超过 200 字符，正文不能为空");
        }
        if (platformTypes == null || platformTypes.isEmpty() || platformTypes.stream().anyMatch(type -> type == null)) {
            throw new IllegalArgumentException("至少选择一个有效平台");
        }
        optimizationRecordService.findById(id).orElseThrow(() -> new NoSuchElementException("内容记录不存在"));

        // 2. 每个平台先插入初始化记录及确认稿快照，再进入预发布中。
        for (PublicationPlatformType platformType : new LinkedHashSet<>(platformTypes)) {
            if (publishRecordService.createInitializedRecord(id, platformType,
                    publicationTitle.trim(), publicationContent)) {
                publishRecordService.startPrePublish(id, platformType);

                // 3. 逐平台调用并立即回写草稿结果；明确失败的旧记录由持久化服务清理后可重新调用。
                PublicationRequest request = PublicationRequest.builder()
                        .title(publicationTitle.trim()).content(publicationContent)
                        .platformTypes(List.of(platformType)).build();
                for (PublicationResult result : publicationService.prePublishContent(request)) {
                    publishRecordService.savePrePublishResult(id, result);
                }
            }
        }
        return publishRecordService.listByContentOptimizationRecordId(id);
    }
}
