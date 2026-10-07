package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentPublishRecordEntity;

/**
 * 平台发布记录 DAO；唯一键去重，条件删除失败记录，条件更新约束状态迁移。
 */
@Mapper
public interface ContentPublishRecordDao extends BaseMapper<ContentPublishRecordEntity> {

    /**
     * 删除非执行中的本地发布记录；条件写入避免状态刚进入执行中时被误删。
     *
     * @param id 发布记录 ID
     * @return 删除的记录数
     */
    @Delete("DELETE FROM content_publish_record WHERE id = #{id} AND publish_status NOT IN (1, 3)")
    int deleteNonProcessingRecord(@Param("id") long id);

    /**
     * 仅删除无平台草稿信息的预发布失败记录，为同平台重新创建确认稿腾出唯一键。
     *
     * @param contentOptimizationRecordId 内容优化记录标识
     * @param platformType 目标平台
     * @return 删除的记录数
     */
    @Delete("""
            DELETE FROM content_publish_record
            WHERE content_optimization_record_id = #{contentOptimizationRecordId}
              AND platform_type = #{platformType} AND publish_status = 5
              AND remote_content_id IS NULL AND draft_url IS NULL AND published_url IS NULL
            """)
    int deleteFailedPrePublishRecord(@Param("contentOptimizationRecordId") long contentOptimizationRecordId,
                                     @Param("platformType") PublicationPlatformType platformType);

    /**
     * 首次选择平台时保存确认稿快照；唯一键冲突由持久化服务识别并跳过。
     *
     * @param contentOptimizationRecordId 内容优化记录标识
     * @param platformType 目标平台
     * @param publicationTitle 用户确认的标题
     * @param publicationContent 用户确认的正文
     * @return 新插入的记录数
     */
    @Insert("""
            INSERT INTO content_publish_record
              (content_optimization_record_id, platform_type, publication_title, publication_content, publish_status)
            VALUES (#{contentOptimizationRecordId}, #{platformType}, #{publicationTitle}, #{publicationContent}, 0)
            """)
    int createInitializedRecord(@Param("contentOptimizationRecordId") long contentOptimizationRecordId,
                                @Param("platformType") PublicationPlatformType platformType,
                                @Param("publicationTitle") String publicationTitle,
                                @Param("publicationContent") String publicationContent);

    /**
     * 仅将刚创建的记录推进到预发布中，避免重复点击触发外部调用。
     *
     * @param contentOptimizationRecordId 内容优化记录标识
     * @param platformType 目标平台
     * @return 更新的记录数
     */
    @Update("""
            UPDATE content_publish_record SET publish_status = 1
            WHERE content_optimization_record_id = #{contentOptimizationRecordId} AND platform_type = #{platformType}
              AND publish_status = 0
            """)
    int startPrePublish(@Param("contentOptimizationRecordId") long contentOptimizationRecordId,
                        @Param("platformType") PublicationPlatformType platformType);

    /**
     * 仅回写预发布中的平台记录，保留草稿结果的单次写入语义。
     *
     * @param contentOptimizationRecordId 内容优化记录标识
     * @param platformType 目标平台
     * @param statusCode 本地记录状态编码
     * @param remoteContentId 平台明确返回的内容标识
     * @param draftUrl 草稿编辑链接
     * @param publishedUrl 公开发布链接
     * @param failureReason 失败或待核对原因
     * @return 更新的发布记录数
     */
    @Update("""
            UPDATE content_publish_record
            SET publish_status = #{statusCode}, remote_content_id = #{remoteContentId},
                draft_url = #{draftUrl}, published_url = #{publishedUrl}, failure_reason = #{failureReason}
            WHERE content_optimization_record_id = #{contentOptimizationRecordId} AND platform_type = #{platformType}
              AND publish_status = 1
            """)
    int savePrePublishResult(@Param("contentOptimizationRecordId") long contentOptimizationRecordId,
                             @Param("platformType") PublicationPlatformType platformType,
                             @Param("statusCode") int statusCode,
                             @Param("remoteContentId") String remoteContentId,
                             @Param("draftUrl") String draftUrl,
                             @Param("publishedUrl") String publishedUrl,
                             @Param("failureReason") String failureReason);
}
