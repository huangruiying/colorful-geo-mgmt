package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.application.project.ProjectBusiness;
import org.huangry.colorful.geo.application.project.model.CreateProjectRequest;
import org.huangry.colorful.geo.application.project.model.ProjectInfoPage;
import org.huangry.colorful.geo.application.project.model.ProjectInfoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

/** 项目管理接口；POST 创建基础档案，GET 分页查询，不管理监测或分析任务。 */
@RestController
@RequestMapping("projects")
@Slf4j
public class MgmtProjectInfoController {
    @Resource
    private ProjectBusiness projectBusiness;

    /** 创建项目并返回包含项目 ID 的档案；业务资料不写入日志。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectInfoResponse createProject(@RequestBody CreateProjectRequest request) {
        ProjectInfoResponse project = projectBusiness.createProject(request);
        log.info("创建项目完成 projectId={}", project.getId());
        return project;
    }

    /** 按名称关键词查询项目，默认第一页、每页 20 条。 */
    @GetMapping
    public ProjectInfoPage listProjects(@RequestParam(value = "keyword", required = false) String keyword,
                                        @RequestParam(value = "page", defaultValue = "0") int page,
                                        @RequestParam(value = "size", defaultValue = "20") int size) {
        return projectBusiness.listProjects(keyword, page, size);
    }

    /** 将基础资料及分页校验失败转换为可读的 HTTP 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidRequest(IllegalArgumentException exception) {
        log.warn("项目管理输入校验失败 httpStatus=400");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
