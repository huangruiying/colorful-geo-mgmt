package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.application.project.ProjectBusiness;
import org.huangry.colorful.geo.application.project.model.CreateProjectRequest;
import org.huangry.colorful.geo.application.project.model.ProjectInfoResponse;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * 项目列表分页查询，可按名称筛选。
     *
     * @param keyword 项目名称关键词，可为空
     * @param page 从零开始的页码，默认 0
     * @param size 每页条数，默认 20
     * @return 当前页项目及总数
     */
    @GetMapping
    public PageResponse<ProjectInfoResponse> listProjects(@RequestParam(value = "keyword", required = false) String keyword,
                                        @RequestParam(value = "page", defaultValue = "0") int page,
                                        @RequestParam(value = "size", defaultValue = "20") int size) {
        return projectBusiness.listProjects(keyword, page, size);
    }
}
