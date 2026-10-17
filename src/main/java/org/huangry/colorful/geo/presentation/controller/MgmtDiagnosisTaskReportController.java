package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.DiagnosisTaskReportBusiness;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisTaskReportResponse;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisTaskResponse;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;


/**
 * 诊断报告入口；生成立即返回，判定与汇总由后台执行器完成，关闭页面不影响生成。
 */
@RestController
@RequestMapping("/diagnosis-reports")
public class MgmtDiagnosisTaskReportController {
	@Resource
	private DiagnosisTaskReportBusiness diagnosisTaskReportBusiness;

	/**
	 * 生成诊断报告。
	 *
	 * @param diagnosisTaskId 诊断任务 ID（geo_diagnosis_task.id）
	 * @return 新建的报告
	 */
	@PostMapping("/diagnosis-tasks/{diagnosisTaskId}")
	@ResponseStatus(HttpStatus.CREATED)
	public DiagnosisTaskReportResponse createReport(@PathVariable("diagnosisTaskId") long diagnosisTaskId) {
		return diagnosisTaskReportBusiness.createReport(diagnosisTaskId);
	}

	/**
	 * 诊断报告列表分页查询，可按诊断任务和状态筛选。
	 *
	 * @param diagnosisTaskId 可选，按诊断任务 ID（geo_diagnosis_task.id）筛选
	 * @param status          报告状态
	 * @param page            页码
	 * @param size            每页数量
	 * @return 当前页报告及总数
	 */
	@GetMapping
	public PageResponse<DiagnosisTaskReportResponse> listReports(@RequestParam(value = "diagnosisTaskId", required = false) Long diagnosisTaskId, @RequestParam(value = "status", required = false) String status, @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size) {
		return diagnosisTaskReportBusiness.listReports(diagnosisTaskId, status, page, size);
	}

	/**
	 * 查看报告详情与冻结汇总。
	 *
	 * @param id 报告 ID
	 * @return 报告详情
	 */
	@GetMapping("/{id}")
	public DiagnosisTaskReportResponse getReport(@PathVariable("id") long id) {
		return diagnosisTaskReportBusiness.getReport(id);
	}

	/**
	 * 数据库或快照异常不透传 SQL 与内部数据。
	 *
	 * @param exception 内部读取或持久化失败
	 * @return 安全错误提示
	 */
	@ExceptionHandler({DataAccessException.class, IllegalStateException.class})
	public ProblemDetail storageFailed(RuntimeException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "诊断报告数据暂时无法处理，请稍后重试");
	}
}
