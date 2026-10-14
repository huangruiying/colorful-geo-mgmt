package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanExecRecordResultEntity;

/** geo_diagnosis_plan_exec_record_result 持久化入口，提供 BaseMapper 增删改查；采样唯一性由数据库约束，事务由调用方负责。 */
@Mapper
public interface GeoDiagnosisPlanExecRecordResultDao extends BaseMapper<GeoDiagnosisPlanExecRecordResultEntity> {
    /**
     * 分批初始化待执行采样，避免最多四万次单条数据库往返。
     * @param items 同一创建事务内的一批采样，状态由调用方通过采样状态枚举初始化
     * @return 插入数量
     */
    @Insert({"<script>",
            "INSERT INTO geo_diagnosis_plan_exec_record_result",
            "(exec_record_id,question_id,question_sort_order,question_snapshot,channel_id,channel_snapshot,repeat_index,status) VALUES",
            "<foreach collection='items' item='item' separator=','>",
            "(#{item.execRecordId},#{item.questionId},#{item.questionSortOrder},#{item.questionSnapshot},#{item.channelId},#{item.channelSnapshot},#{item.repeatIndex},#{item.status})",
            "</foreach>", "</script>"})
    int insertSamples(@Param("items") List<GeoDiagnosisPlanExecRecordResultEntity> items);

    /**
     * 按任务与渠道聚合状态，所有统计以已持久化结果为准。
     * @param taskId 执行记录 ID
     * @return 各渠道各状态数量
     */
    @Select("SELECT channel_id,status,COUNT(*) AS sample_count FROM geo_diagnosis_plan_exec_record_result WHERE exec_record_id=#{taskId} GROUP BY channel_id,status")
    List<Map<String, Object>> countStatuses(@Param("taskId") long taskId);
}
