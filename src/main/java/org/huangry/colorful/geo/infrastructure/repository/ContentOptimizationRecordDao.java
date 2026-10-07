package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.huangry.colorful.geo.application.content.model.ContentOptimizationRecordSummary;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentOptimizationRecordEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内容记录 DAO；提供 MyBatis-Plus 基础读写与列表聚合 SQL，不编排审核流程。
 */
@Mapper
public interface ContentOptimizationRecordDao extends BaseMapper<ContentOptimizationRecordEntity> {

    /**
     * 只查询页面所需的摘要与平台发布数量，避免分页时加载正文。
     *
     * @param size 每页条数
     * @param offset 起始偏移
     * @return 当前页摘要
     */
    @Select("""
            SELECT r.id, r.title, r.created_at,
                   COUNT(t.id) AS publish_record_count,
                   COALESCE(SUM(t.publish_status = 2), 0) AS draft_count,
                   COALESCE(SUM(t.publish_status IN (1, 3)), 0) AS processing_count,
                   COALESCE(SUM(t.publish_status IN (5, 6)), 0) AS failed_count
            FROM content_optimization_record r
            LEFT JOIN content_publish_record t ON t.content_optimization_record_id = r.id
            GROUP BY r.id, r.title, r.created_at
            ORDER BY r.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = long.class, id = true),
            @Arg(column = "title", javaType = String.class),
            @Arg(column = "publish_record_count", javaType = int.class),
            @Arg(column = "draft_count", javaType = int.class),
            @Arg(column = "processing_count", javaType = int.class),
            @Arg(column = "failed_count", javaType = int.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class)
    })
    List<ContentOptimizationRecordSummary> listSummaries(@Param("size") int size, @Param("offset") long offset);

}
