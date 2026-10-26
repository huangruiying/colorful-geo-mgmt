package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.application.project.ProjectInfoImportExportBusiness;
import org.huangry.colorful.geo.application.project.model.ProjectImportResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 项目导入导出接口：把单个项目导出为 JSON 文件下载，或上传 JSON 文件导入为一个新项目。
 * <p>仅已登录管理员可用，沿用全局输入错误处理和 CSRF 防护；上传按 50 MiB 上限有界读取。</p>
 * <p>数据库约束不满足返回 400，数据库访问失败返回 500，两种情况导入都已回滚。</p>
 */
@RestController
@RequestMapping("project-info-import-export")
@Slf4j
public class MgmtProjectInfoImportExportController {
    @Resource
    private ProjectInfoImportExportBusiness importExportBusiness;

    /**
     * 导出单个项目为 JSON 附件；文件名只含数字 ID，不把项目名称写入响应头。
     * @param projectId 项目 ID
     * @return 项目 JSON 文件
     */
    @GetMapping(value = "/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportProject(@PathVariable long projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=project-" + projectId + ".json")
                .contentType(MediaType.APPLICATION_JSON).body(importExportBusiness.exportProject(projectId));
    }

    /**
     * 接收有界 JSON 文件后整体导入；读取失败不自动重发，重复成功提交会新建另一份项目。
     * @param request 原始 JSON 文件请求，用流读取避免超限正文全部进入内存
     * @return 新项目 ID 与各类数据数量
     * @throws IOException 请求连接在读取文件时中断
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectImportResponse importProject(HttpServletRequest request) throws IOException {
        // 1. 文件长度未知或被伪造时仍按读取上限截断，解析前统一检查实际体积
        byte[] bytes = request.getInputStream().readNBytes(ProjectInfoImportExportBusiness.MAX_FILE_BYTES + 1);
        // 2. 所有数据库写入由代理服务的同一事务完成，控制器不拆分多次提交
        ProjectImportResponse response = importExportBusiness.importProject(bytes);
        // 3. 事务代理返回后才记录完成，避免提交失败仍留下成功日志
        log.info("项目导入完成 projectId={} tasks={} reports={}", response.projectId(),
                response.counts().get("tasks"), response.counts().get("reports"));
        return response;
    }

    /**
     * 文件不满足目标数据库字段或唯一约束时返回可理解原因；不打印可能含正文的 SQL 异常。
     * @param exception 数据完整性异常，不回显数据库明细
     * @return HTTP 400，事务已回滚
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail invalidArchiveData(DataIntegrityViolationException exception) {
        log.warn("项目导入数据不满足数据库约束 exceptionType={}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "项目文件的字段、关联或重复记录不符合要求，导入已回滚");
    }

    /**
     * 数据库访问失败时不泄露 SQL、文件内容或连接信息。
     * @param exception 数据访问异常
     * @return HTTP 500，提示核对服务状态
     */
    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    public ProblemDetail archiveStorageFailed(RuntimeException exception) {
        log.error("项目数据迁移失败 exceptionType={}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "项目数据迁移失败，请检查数据库与服务状态；导入失败的记录已回滚");
    }
}
