package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 映射 geo_diagnosis_answer_analysis，保存一次报告中单条有效回答的分析结果；通过 DAO 持久化，不负责判定。
 * <p>问题分组、引用口径、是否有来源、是否实际联网可由诊断结果快照、引用与原始响应推导，不在此重复保存。</p>
 */
@Data
@TableName("geo_diagnosis_answer_analysis")
public class GeoDiagnosisAnswerAnalysisEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属诊断报告 ID，与诊断结果 ID 组成唯一键。 */
    private Long reportId;
    /** 被分析的诊断结果 ID，只包含成功且未因输出上限截断的结果。 */
    private Long resultId;
    /** 判定状态，保存 {@link org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisAnswerAnalysisStatus} 的枚举名称。 */
    private String status;
    /** 点名问题中模型对目标的评价倾向，保存 {@link org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisBrandSentiment} 的枚举名称；其他问题为空。 */
    private String brandSentiment;
    /** 点名问题中对目标的描述是否准确；其他问题为空。 */
    private Boolean descriptionAccurate;
    /** JSON 字符串：回答中出现的目标、竞品及待确认产品，含是否推荐、排名与推荐强度；目标未出现时不含目标项。 */
    private String brands;
    /** JSON 字符串：选择标准、用户顾虑、正负面点、错误信息、推荐理由、保留意见与维度胜负，含原文片段和证据状态。 */
    private String points;
    /** JSON 字符串：回答来源的网址、平台、来源类型及匹配到的发布记录。 */
    private String sources;
    /** 脱敏后的判定失败原因，不保存远程异常原文。 */
    private String errorMessage;
    /** 判定结果是否经过人工修正；修正后重新判定不得静默覆盖。 */
    private Boolean manuallyCorrected;
    /** 由数据库生成的创建时间。 */
    private LocalDateTime createdAt;
    /** 由数据库维护的最后更新时间。 */
    private LocalDateTime updatedAt;
}
