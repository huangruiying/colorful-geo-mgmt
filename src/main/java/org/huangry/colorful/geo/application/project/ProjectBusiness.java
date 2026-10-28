package org.huangry.colorful.geo.application.project;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.project.model.CreateProjectRequest;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoProjectInfoEntity;
import org.huangry.colorful.geo.application.project.model.ProjectInfoResponse;
import org.huangry.colorful.geo.infrastructure.common.model.PageQuery;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.huangry.colorful.geo.domain.service.project.GeoProjectInfoService;
import org.springframework.stereotype.Service;

/** 项目应用编排；创建输入转换、列表分页与删除入口在此处理，资料规则及删除范围由项目领域服务负责。 */
@Service
public class ProjectBusiness {
    @Resource
    private GeoProjectInfoService projectService;

    /** 将创建请求转换为基础档案并保存，不允许输入覆盖数据库 ID 或时间。 */
    public ProjectInfoResponse createProject(CreateProjectRequest request) {
        // 转换页面输入，系统字段没有对应的请求属性。
        GeoProjectInfoEntity project = new GeoProjectInfoEntity();
        project.setName(request.getName());
        project.setWebsiteUrl(request.getWebsiteUrl());
        project.setIndustry(request.getIndustry());
        project.setCoreFeatures(request.getCoreFeatures());
        project.setTargetAudience(request.getTargetAudience());
        project.setAliases(request.getAliases());
        project.setCompetitors(request.getCompetitors());
        project.setTargetMarket(request.getTargetMarket());
        project.setTargetLanguage(request.getTargetLanguage());
        project.setSellingPoints(request.getSellingPoints());
        project.setPricing(request.getPricing());
        project.setKeywords(request.getKeywords());
        // 交由领域校验与持久化，返回可关联的项目 ID。
        return toResponse(projectService.createProject(project));
    }

    /**
     * 项目列表分页查询，可按名称筛选。
     *
     * @param keyword 项目名称关键词，可为空
     * @param page 从零开始的页码
     * @param size 每页条数，上限 100
     * @return 当前页项目及总数
     */
    public PageResponse<ProjectInfoResponse> listProjects(String keyword, int page, int size) {
        // 分页参数和关键词越界直接拒绝，避免无界加载基础档案
        var pageQuery = new PageQuery(page, size, keyword);

        // 列表和总数使用相同名称条件
        var items = projectService.listProjects(pageQuery).stream()
            .map(this::toResponse)
            .toList();
        long total = projectService.countProjects(pageQuery.keyword());
        return pageQuery.toResponse(items, total);
    }

    /**
     * 删除项目及其全部诊断方案、任务、诊断结果、报告和回答分析，执行中的任务和报告一并删除。
     *
     * @param projectId 项目 ID
     * @throws java.util.NoSuchElementException 项目不存在
     */
    public void deleteProject(long projectId) {
        projectService.deleteProject(projectId);
    }

    /** 显式转换接口字段，持久化实体只在内部流转。 */
    private ProjectInfoResponse toResponse(GeoProjectInfoEntity entity) {
        var response = new ProjectInfoResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setWebsiteUrl(entity.getWebsiteUrl());
        response.setIndustry(entity.getIndustry());
        response.setCoreFeatures(entity.getCoreFeatures());
        response.setTargetAudience(entity.getTargetAudience());
        response.setAliases(entity.getAliases());
        response.setCompetitors(entity.getCompetitors());
        response.setTargetMarket(entity.getTargetMarket());
        response.setTargetLanguage(entity.getTargetLanguage());
        response.setSellingPoints(entity.getSellingPoints());
        response.setPricing(entity.getPricing());
        response.setKeywords(entity.getKeywords());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }
}
