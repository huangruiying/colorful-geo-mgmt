package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.ContentPublishRecordStatus;

import java.time.LocalDateTime;

/**
 * content_publish_record 表映射；保存单个平台的确认稿快照及发布结果。
 */
@Data
@TableName(value = "content_publish_record", autoResultMap = true)
public class ContentPublishRecordEntity {

    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属内容优化记录 ID，仅作逻辑关联。 */
    private Long contentOptimizationRecordId;
    /** 投放平台。 */
    private PublicationPlatformType platformType;
    /** 用户确认时的标题快照。 */
    private String publicationTitle;
    /** 用户确认时的正文快照。 */
    private String publicationContent;
    /** 逐平台发布状态，使用 {@link ContentPublishRecordStatus}，数据库存储其数字编码。 */
    private ContentPublishRecordStatus publishStatus;
    /** 平台明确返回的内容 ID。 */
    private String remoteContentId;
    /** 草稿编辑链接。 */
    private String draftUrl;
    /** 公开发布后的链接。 */
    private String publishedUrl;
    /** 失败或待核对原因。 */
    private String failureReason;
    /** 任务创建时间。 */
    private LocalDateTime createdAt;
    /** 最近修改时间。 */
    private LocalDateTime updatedAt;
}
