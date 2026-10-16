package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisResultAnalysisEntity;

import java.util.List;
import java.util.Map;

/** geo_diagnosis_result_analysis 持久化入口，提供 BaseMapper 增删改查；报告与回答的唯一性由数据库约束，事务由调用方负责。 */
@Mapper
public interface GeoDiagnosisResultAnalysisDao extends BaseMapper<GeoDiagnosisResultAnalysisEntity> {
    /**
     * 按任务的诊断结果批量建立待判定记录，一条 SQL 完成，避免逐条往返。
     * <p>哪些结果纳入分析由调用方通过参数决定，DAO 不判断业务口径。</p>
     * @param outputId 新建的报告 ID
     * @param taskId 诊断任务 ID
     * @param resultStatus 纳入分析的诊断结果状态
     * @param excludedFinishReason 需排除的结束原因（输出被截断），结束原因为空的结果保留
     * @param analysisStatus 新记录的判定状态
     * @return 建立的分析记录数
     */
    @Insert("INSERT INTO geo_diagnosis_result_analysis (output_id,result_id,status) "
            + "SELECT #{outputId},id,#{analysisStatus} FROM geo_diagnosis_plan_exec_record_result "
            + "WHERE exec_record_id=#{taskId} AND status=#{resultStatus} "
            + "AND (finish_reason IS NULL OR finish_reason<>#{excludedFinishReason}) ORDER BY id")
    int insertPendingFromResults(@Param("outputId") long outputId, @Param("taskId") long taskId,
            @Param("resultStatus") String resultStatus, @Param("excludedFinishReason") String excludedFinishReason,
            @Param("analysisStatus") String analysisStatus);

    /**
     * 按报告与判定状态统计分析记录数量，一次查询覆盖列表页的多份报告。
     * @param outputIds 报告 ID，不能为空集合
     * @return 每行含 output_id、status、analysis_count
     */
    @Select({"<script>",
            "SELECT output_id,status,COUNT(*) AS analysis_count FROM geo_diagnosis_result_analysis WHERE output_id IN",
            "<foreach collection='outputIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY output_id,status", "</script>"})
    List<Map<String, Object>> countStatuses(@Param("outputIds") List<Long> outputIds);
}
