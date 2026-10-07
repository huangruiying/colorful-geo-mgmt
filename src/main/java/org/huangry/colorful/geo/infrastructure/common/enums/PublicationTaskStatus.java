package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 单个平台投放任务状态。
 *
 * <p>内容整体状态由多个平台任务聚合得出，不能用该状态直接替代内容生命周期状态。</p>
 *
 * @author huangry
 */
public enum PublicationTaskStatus {

    /** 待发布：任务尚未开始投放。 */
    PENDING,

    /** 草稿已创建：内容已保存到平台草稿箱，尚未公开发布。 */
    DRAFT_CREATED,

    /** 发布中：投放已开始，尚未确认最终发布结果。 */
    PUBLISHING,

    /** 已发布：已确认内容在目标平台发布成功，不用于表示仅创建草稿。 */
    PUBLISHED,

    /** 发布失败：本次投放未完成，失败原因由结果或任务记录保存。 */
    FAILED,

    /** 已取消：任务停止投放，不表示删除已经发布的平台内容。 */
    CANCELLED
}
