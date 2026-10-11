package org.huangry.colorful.geo.application.project;

import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.application.project.model.CreateProjectCommand;
import org.huangry.colorful.geo.application.project.model.ProjectPage;
import org.huangry.colorful.geo.domain.model.Project;
import org.huangry.colorful.geo.domain.service.project.ProjectService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/** 项目应用编排；创建输入转换与列表分页在此处理，资料规则由项目领域服务负责。 */
@Service
@RequiredArgsConstructor
public class ProjectBusiness {
    private final ProjectService projectService;

    /** 将创建命令转换为基础档案并保存，不允许输入覆盖数据库 ID 或时间。 */
    public Project createProject(CreateProjectCommand command) {
        // 转换页面输入，系统字段没有对应的命令属性。
        Project project = new Project();
        BeanUtils.copyProperties(command, project);
        // 交由领域校验与持久化，返回可关联的项目 ID。
        return projectService.createProject(project);
    }

    /** 校验分页条件并返回名称筛选后的项目列表，页码从零开始。 */
    public ProjectPage listProjects(String keyword, int page, int size) {
        // 分页边界限定为有限查询，避免无界加载基础档案。
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("页码不能小于零，每页条数必须在 1 到 100 之间");
        if (keyword != null && keyword.length() > 200) throw new IllegalArgumentException("查询关键词最多 200 字符");
        String normalizedKeyword = keyword == null ? null : keyword.strip();
        // 列表和计数使用相同名称条件，偏移计算使用 long 防止溢出。
        return new ProjectPage(projectService.listProjects(normalizedKeyword, size, (long) page * size),
                projectService.countProjects(normalizedKeyword), page, size);
    }
}
