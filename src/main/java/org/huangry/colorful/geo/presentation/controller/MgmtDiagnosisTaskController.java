package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.DiagnosisTaskBusiness;
import org.huangry.colorful.geo.application.diagnosis.model.CreateDiagnosisTaskRequest;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisTaskResponse;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

/** 诊断任务入口；创建立即返回，后台执行及回答查询独立于浏览器请求。 */
@RestController
@RequestMapping("/diagnosis-tasks")
public class MgmtDiagnosisTaskController {
    @Resource
    private DiagnosisTaskBusiness diagnosisTaskBusiness;

    /**
     * 发起或回读相同创建提交，不直接执行模型调用。
     * @param request 方案及提交标识
     * @return 已创建任务
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisTaskResponse createTask(@RequestBody CreateDiagnosisTaskRequest request) {
        return diagnosisTaskBusiness.createTask(request);
    }

    /**
     * 重置失败或部分失败的任务：非成功的诊断结果改回待执行，成功结果保留；不创建新任务，也不在接口中直接调用模型。
     * @param id 诊断任务 ID
     * @return 重置后的任务详情
     */
    @PostMapping("/{id}/restart")
    public DiagnosisTaskResponse restartTask(@PathVariable("id") long id) {
        return diagnosisTaskBusiness.restartTask(id);
    }

    /**
     * 诊断任务列表分页查询，可按方案和状态筛选。
     * @param planId 方案 ID
     * @param status 状态
     * @param page 页码
     * @param size 每页数量
     * @return 当前页任务及总数
     */
    @GetMapping
    public PageResponse<DiagnosisTaskResponse> listTasks(
            @RequestParam(value = "planId", required = false) Long planId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return diagnosisTaskBusiness.listTasks(planId, status, page, size);
    }

    /**
     * 查看冻结配置和实时进度。
     * @param id 任务 ID
     * @return 任务详情
     */
    @GetMapping("/{id}")
    public DiagnosisTaskResponse getTask(@PathVariable("id") long id) {
        return diagnosisTaskBusiness.getTask(id);
    }

    /**
     * 诊断结果列表分页查询，可按状态和渠道筛选。
     * @param id 任务 ID
     * @param status 诊断结果状态
     * @param channelId 渠道 ID
     * @param page 页码
     * @param size 每页数量
     * @return 当前页诊断结果及总数
     */
    @GetMapping("/{id}/results")
    public PageResponse<DiagnosisTaskResponse.ResultResponse> listTaskResults(@PathVariable("id") long id,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "channelId", required = false) Long channelId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return diagnosisTaskBusiness.listTaskResults(id, status, channelId, page, size);
    }

    /**
     * 查看一条完整回答，不允许跨任务读取。
     * @param id 任务 ID
     * @param resultId 诊断结果 ID
     * @return 回答详情
     */
    @GetMapping("/{id}/results/{resultId}")
    public DiagnosisTaskResponse.ResultResponse getTaskResult(@PathVariable("id") long id, @PathVariable("resultId") long resultId) {
        return diagnosisTaskBusiness.getTaskResult(id, resultId);
    }

    /**
     * 无效模型配置不创建任务，不回传远程异常。
     * @param exception 渠道校验失败
     * @return 配置错误
     */
    @ExceptionHandler(LLMClientException.class)
    public ProblemDetail channelInvalid(LLMClientException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "测试渠道不可用，请检查启用状态、策略、地址、模型和密钥");
    }

    /**
     * 数据库或快照异常不透传 SQL、凭据和内部数据。
     * @param exception 内部读取或持久化失败
     * @return 安全错误提示
     */
    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    public ProblemDetail storageFailed(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "诊断任务数据暂时无法处理，请稍后重试");
    }
}
