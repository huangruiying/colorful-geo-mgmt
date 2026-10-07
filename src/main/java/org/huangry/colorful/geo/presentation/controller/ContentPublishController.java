package org.huangry.colorful.geo.presentation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.application.content.ContentPublishBusiness;
import org.huangry.colorful.geo.application.content.model.ContentPublishRecordPage;
import org.huangry.colorful.geo.domain.model.ContentPublishRecord;
import org.huangry.colorful.geo.presentation.controller.model.PrePublishContentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 内容发布接口；提供预发布动作及本地发布记录管理，不删除平台上的草稿或文章。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("content-publish")
public class ContentPublishController {

    private final ContentPublishBusiness publishBusiness;

    /**
     * 用户确认最终稿与平台后创建草稿；重复选择已有发布记录不会重复提交。
     *
     * @param id 内容记录 ID
     * @param request 确认稿和目标平台列表
     * @return 该内容的全部平台发布记录
     */
    @PostMapping("{id}/pre-publish")
    public List<ContentPublishRecord> prePublish(@PathVariable("id") long id,
                                                 @RequestBody PrePublishContentRequest request) {
        log.info("创建平台草稿 request optimizationRecordId={} platforms={} contentLength={}",
                id, request.platformTypes(), request.publicationContent() == null ? 0 : request.publicationContent().length());
        List<ContentPublishRecord> records = publishBusiness.prePublish(id, request.publicationTitle(),
                request.publicationContent(), request.platformTypes());
        log.info("创建平台草稿 response optimizationRecordId={} records={}", id,
                records.stream().map(record -> record.getPlatformType() + ":" + record.getPublishStatus()).toList());
        return records;
    }

    /**
     * 读取发布记录分页，状态以本地发布表为准。
     *
     * @param page 从零开始的页码
     * @param size 每页条数
     * @return 发布记录分页
     */
    @GetMapping("list")
    public ContentPublishRecordPage listContentPublishRecords(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        log.info("查询内容发布记录列表 request page={} size={}", page, size);
        ContentPublishRecordPage records = publishBusiness.listContentPublishRecords(page, size);
        log.info("查询内容发布记录列表 response total={} itemCount={}", records.total(), records.items().size());
        return records;
    }

    /**
     * 删除一条非执行中的本地发布记录，保留平台侧内容。
     *
     * @param id 发布记录 ID
     */
    @DeleteMapping("detail/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteContentPublishRecord(@PathVariable("id") long id) {
        log.info("删除内容发布记录 request publishRecordId={}", id);
        publishBusiness.deleteContentPublishRecord(id);
        log.info("删除内容发布记录 response publishRecordId={} httpStatus=204", id);
    }

    /** 参数错误返回 400，避免被误认为数据库故障。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidRequest(IllegalArgumentException exception) {
        log.warn("内容发布接口 response httpStatus=400 errorType={}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /** 记录已被其他操作删除时返回 404。 */
    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missingRecord(NoSuchElementException exception) {
        log.warn("内容发布接口 response httpStatus=404 errorType={}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /** 平台任务仍在执行时返回 409，提示用户先核对结果。 */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail processingRecord(IllegalStateException exception) {
        log.warn("内容发布接口 response httpStatus=409 errorType={}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
