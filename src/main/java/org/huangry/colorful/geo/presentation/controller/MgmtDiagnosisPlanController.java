package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.DiagnosisPlanBusiness;
import org.huangry.colorful.geo.application.diagnosis.model.*;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 诊断方案管理入口；候选生成与保存分离，不调用被测渠道执行采样。
 */
@RestController
@RequestMapping("/diagnosis-plans")
public class MgmtDiagnosisPlanController {
	@Resource
	private DiagnosisPlanBusiness business;

	/**
	 * 创建完整方案，子集合与主表同时保存。
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public DiagnosisPlanResponse create(@RequestBody SaveDiagnosisPlanRequest request) {
		return business.save(null, request);
	}

	/**
	 * 修改方案的配置和问题集合，不改变任何诊断任务。
	 */
	@PutMapping("/{id}")
	public DiagnosisPlanResponse update(@PathVariable("id") long id, @RequestBody SaveDiagnosisPlanRequest request) {
		return business.save(id, request);
	}

	/**
	 * 返回可编辑的完整方案。
	 */
	@GetMapping("/{id}")
	public DiagnosisPlanResponse detail(@PathVariable("id") long id) {
		return business.detail(id);
	}

	/**
	 * 按名称分页查询方案。
	 */
	@GetMapping
	public DiagnosisPlanBusiness.PlanPage list(@RequestParam(value = "keyword", required = false) String keyword,
	                                           @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size) {
		return business.list(keyword, page, size);
	}

	/**
	 * 返回待确认的问题，生成成功也不会自动落库。
	 */
	@PostMapping("/generate-questions")
	public List<DiagnosisQuestion> generate(@RequestBody GenerateDiagnosisQuestionsRequest request) {
		return business.generate(request);
	}

	/**
	 * 输入或集合不合法时，保留客户端已编辑内容供修正。
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail invalid(IllegalArgumentException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
	}

	/**
	 * 缺失方案不能伪装为创建成功。
	 */
	@ExceptionHandler(NoSuchElementException.class)
	public ProblemDetail missing(NoSuchElementException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
	}

	/**
	 * 不透出远程响应、凭据或模型生成原文。
	 * @param exception 制题或模型调用异常
	 * @return 固定分类的网关失败提示，保留页面已有候选
	 */
	@ExceptionHandler(LLMClientException.class)
	public ProblemDetail generationFailed(LLMClientException exception) {
		// 先按异常链识别连接、超时与 Fastjson 格式错误，不回传远程异常消息
		String reason = "模型调用失败，请检查生成渠道配置或稍后重试";
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof javax.net.ssl.SSLException) {
				reason = "模型网关的 HTTPS 连接失败，请检查网关地址、证书和网络代理";
				break;
			}
			if (cause instanceof java.net.ConnectException) {
				reason = "无法连接模型网关，请检查网关地址及网络连接";
				break;
			}
			if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
				reason = "模型响应超时，请减少生成数量或检查服务的统一 HTTP 超时配置";
				break;
			}
			if (cause instanceof com.alibaba.fastjson.JSONException || cause instanceof IllegalArgumentException) {
				reason = "模型返回的问题格式、数量或品牌标记不符合要求，请重试或更换生成模型";
				break;
			}
		}
		// 所有模型生成失败沿用网关失败状态，页面继续保留用户已有编辑内容
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, reason + "；已有问题和候选保留");
	}
}
