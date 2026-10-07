package org.huangry.colorful.geo.infrastructure.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 单个平台的内容发布状态；数字编码与 content_publish_record.publish_status 一致。
 *
 * <p>主链路：初始化 → 预发布中 → 预发布成功 → 实际发布中 → 实际发布成功。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ContentPublishRecordStatus {

    /** 已保存确认稿，尚未调用平台。 */
    INITIALIZED(0),
    /** 已开始创建草稿；结果不确定时保持本状态等待核对。 */
    PRE_PUBLISHING(1),
    /** 平台明确确认草稿创建成功。 */
    PRE_PUBLISHED(2),
    /** 已开始公开发布已有草稿。 */
    PUBLISHING(3),
    /** 平台明确确认公开发布成功。 */
    PUBLISHED(4),
    /** 提交前失败或平台明确拒绝，可由用户重新预发布。 */
    PRE_PUBLISH_FAILED(5),
    /** 公开发布失败。 */
    PUBLISH_FAILED(6),
    /** 从旧数据迁移的已取消记录。 */
    CANCELLED(7);

    /** 数据库存储的稳定状态编码。 */
    @EnumValue
    private final int code;
}
