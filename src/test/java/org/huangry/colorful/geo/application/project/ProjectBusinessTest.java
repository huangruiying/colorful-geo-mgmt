package org.huangry.colorful.geo.application.project;

import org.huangry.colorful.geo.infrastructure.common.model.PageQuery;
import org.huangry.colorful.geo.domain.service.project.GeoProjectInfoService;
import org.huangry.colorful.geo.application.project.model.CreateProjectRequest;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoProjectInfoEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证分页边界及列表和计数使用同一关键词；数据库行为另做真实集成验证。 */
class ProjectBusinessTest {
    /** 新建和列表响应保持项目标识、列表资料及数据库时间。 */
    @Test
    void shouldReturnSavedInformationForCreationAndList() {
        var service = mock(GeoProjectInfoService.class);
        var business = createBusiness(service);
        var saved = new GeoProjectInfoEntity();
        saved.setId(12L);
        saved.setAliases(List.of("品牌", "Brand"));
        saved.setKeywords(List.of("企业知识库"));
        saved.setCreatedAt(LocalDateTime.of(2026, 10, 11, 12, 0));
        when(service.createProject(any())).thenReturn(saved);
        when(service.listProjects(new PageQuery(0, 20))).thenReturn(List.of(saved));
        when(service.countProjects(null)).thenReturn(1L);
        var created = business.createProject(new CreateProjectRequest());
        var listed = business.listProjects(null, 0, 20).items().get(0);
        assertEquals(created, listed);
        assertEquals(12L, created.getId());
        assertEquals(saved.getAliases(), created.getAliases());
        assertEquals(saved.getKeywords(), created.getKeywords());
        assertEquals(saved.getCreatedAt(), created.getCreatedAt());
    }

    /** 显式注入替身，保持测试与字段注入方式一致。 */
    private ProjectBusiness createBusiness(GeoProjectInfoService service) {
        var business = new ProjectBusiness();
        ReflectionTestUtils.setField(business, "projectService", service);
        return business;
    }

    /** 无效分页不能继续查询数据库。 */
    @Test
    void shouldRejectInvalidPagination() {
        GeoProjectInfoService service = mock(GeoProjectInfoService.class);
        ProjectBusiness business = createBusiness(service);
        assertThrows(IllegalArgumentException.class, () -> business.listProjects(null, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> business.listProjects(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> business.listProjects(null, 0, 101));
        verifyNoInteractions(service);
    }

    /** 列表按零起始分页，关键词去除首尾空白，计数与列表筛选一致。 */
    @Test
    void shouldQueryConsistentFilterAndOffset() {
        GeoProjectInfoService service = mock(GeoProjectInfoService.class);
        when(service.listProjects(new PageQuery(2, 20, "产品"))).thenReturn(List.of());
        when(service.countProjects("产品")).thenReturn(45L);
        var result = createBusiness(service).listProjects(" 产品 ", 2, 20);
        assertEquals(45L, result.total()); assertEquals(2, result.page());
        verify(service).listProjects(new PageQuery(2, 20, "产品"));
        verify(service).countProjects("产品");
    }
}
