package org.huangry.colorful.geo.presentation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.application.content.ContentOptimizationBusiness;
import org.huangry.colorful.geo.application.content.model.ContentOptimizationRecordDetail;
import org.huangry.colorful.geo.application.content.model.ContentOptimizationRecordPage;
import org.huangry.colorful.geo.application.content.model.OptimizeContentCommand;
import org.huangry.colorful.geo.domain.model.ContentOptimizationRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * 内容优化记录接口；提供优化与查询，不接收平台发布动作。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("content-optimize")
public class ContentOptimizationController {

    private final ContentOptimizationBusiness optimizationBusiness;

    /**
     * 优化并保存一篇内容，供页面后续确认稿件和预发布。
     *
     * @param command 标题、原文及可选优化配置
     * @return 已入库的优化内容
     */
    @PostMapping("optimize")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentOptimizationRecord optimize(@RequestBody OptimizeContentCommand command) {
        log.info("创建内容优化记录 request contentLength={} strategyCount={}",
                command.content() == null ? 0 : command.content().length(),
                command.strategies() == null ? 0 : command.strategies().size());
        ContentOptimizationRecord record = optimizationBusiness.optimizeAndSave(command);
        log.info("创建内容优化记录 response recordId={}", record.getId());
        return record;
    }

    /**
     * 优化记录列表。
     *
     * @param page 从零开始的页码
     * @param size 每页条数
     * @return 内容摘要分页
     */
    @GetMapping("list")
    public ContentOptimizationRecordPage list(@RequestParam(value = "page", defaultValue = "0") int page,
                                  @RequestParam(value = "size", defaultValue = "20") int size) {
        log.info("查询内容优化记录列表 request page={} size={}", page, size);
        ContentOptimizationRecordPage records = optimizationBusiness.listContentOptimizationRecords(page, size);
        log.info("查询内容优化记录列表 response total={} itemCount={}", records.total(), records.items().size());
        return records;
    }

    /**
     * 查询单篇内容的原文、优化稿和平台发布记录。
     *
     * @param id 内容记录 ID
     * @return 内容详情
     */
    @GetMapping("detail/{id}")
    public ContentOptimizationRecordDetail detail(@PathVariable("id") long id) {
        log.info("查询内容优化记录详情 request recordId={}", id);
        ContentOptimizationRecordDetail detail = optimizationBusiness.getContentOptimizationRecord(id);
        log.info("查询内容优化记录详情 response recordId={} publishRecordCount={}",
                detail.record().getId(), detail.publishRecords().size());
        return detail;
    }

    /** 将无效输入映射为 400，不将它当作平台或数据库故障。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidRequest(IllegalArgumentException exception) {
        log.warn("内容优化接口 response httpStatus=400 errorType={}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /** 将不存在的记录映射为 404，便于页面提示记录已被移除。 */
    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missingRecord(NoSuchElementException exception) {
        log.warn("内容优化接口 response httpStatus=404 errorType={}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

}
