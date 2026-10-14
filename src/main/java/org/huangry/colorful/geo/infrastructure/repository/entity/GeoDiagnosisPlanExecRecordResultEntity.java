package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 映射 geo_diagnosis_plan_exec_record_result，保存一次独立诊断的问题、渠道快照及模型响应；通过 DAO 持久化，不负责执行调度。 */
@Data
@TableName("geo_diagnosis_plan_exec_record_result")
public class GeoDiagnosisPlanExecRecordResultEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属执行记录 ID，通过外键关联本轮任务。 */
    private Long execRecordId;
    /** 来源问题 ID，仅用于追溯；方案编辑可能重建问题。 */
    private Long questionId;
    /** 创建任务时的问题序号，与渠道和重复序号组成诊断结果唯一键。 */
    private Integer questionSortOrder;
    /** JSON 字符串：问题正文、来源、意图、品牌范围和语言。 */
    private String questionSnapshot;
    /** 诊断结果渠道 ID，仅用于追溯；历史模型配置以快照为准。 */
    private Long channelId;
    /** JSON 字符串：渠道、策略、模型及生成参数等非敏感配置，不保存凭据。 */
    private String channelSnapshot;
    /** 同一问题和渠道的独立诊断序号，从 1 开始，最多 10 次。 */
    private Integer repeatIndex;
    /** 单条诊断结果状态，保存 {@link org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskResultStatus} 的枚举名称。 */
    private String status;
    /** 模型回答正文，未成功时为空。 */
    private String answerText;
    /** 结构化引用的 JSON 数组字符串；成功无引用时保存 []，不从正文推测引用。 */
    private String citations;
    /** 接口返回的实际模型标识，接口未提供时为空。 */
    private String modelName;
    /** 接口返回的 Token 用量 JSON 字符串，未提供时为空。 */
    private String usageInfo;
    /** 本次外部调用的耗时，单位毫秒。 */
    private Long durationMs;
    /** 接口返回的生成结束原因，未提供时为空。 */
    private String finishReason;
    /** 模型响应体，不包含请求认证头或凭据。 */
    private String rawResponse;
    /** 脱敏后的失败原因，不直接保存远程异常原文。 */
    private String errorMessage;
    /** 本次诊断开始时间，尚未执行时为空。 */
    private LocalDateTime startedAt;
    /** 本次诊断结束时间，尚未结束时为空。 */
    private LocalDateTime finishedAt;
    /** 由数据库生成的创建时间，插入后重新查询回读。 */
    private LocalDateTime createdAt;
    /** 由数据库维护的最后更新时间，更新后重新查询回读。 */
    private LocalDateTime updatedAt;
}
