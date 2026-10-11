package org.huangry.colorful.geo.application.project;

import org.huangry.colorful.geo.domain.service.project.GeoProjectInfoService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证分页边界及列表和计数使用同一关键词；数据库行为另做真实集成验证。 */
class ProjectBusinessTest {
    /** 无效分页不能继续查询数据库。 */
    @Test
    void shouldRejectInvalidPagination() {
        GeoProjectInfoService service = mock(GeoProjectInfoService.class);
        ProjectBusiness business = new ProjectBusiness(service);
        assertThrows(IllegalArgumentException.class, () -> business.listProjects(null, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> business.listProjects(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> business.listProjects(null, 0, 101));
        verifyNoInteractions(service);
    }

    /** 列表按零起始分页，关键词去除首尾空白，计数与列表筛选一致。 */
    @Test
    void shouldQueryConsistentFilterAndOffset() {
        GeoProjectInfoService service = mock(GeoProjectInfoService.class);
        when(service.listProjects("产品", 20, 40L)).thenReturn(List.of());
        when(service.countProjects("产品")).thenReturn(45L);
        var result = new ProjectBusiness(service).listProjects(" 产品 ", 2, 20);
        assertEquals(45L, result.total()); assertEquals(2, result.page());
        verify(service).listProjects("产品", 20, 40L);
        verify(service).countProjects("产品");
    }
}
