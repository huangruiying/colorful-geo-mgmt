package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.domain.service.project.ProjectArchiveFormat;
import org.huangry.colorful.geo.domain.service.project.ProjectArchiveService;
import org.huangry.colorful.geo.domain.service.project.ProjectImportResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 文件迁移 HTTP 契约回归；数据库事务由领域服务测试，登录与 CSRF 沿用统一安全配置。 */
class MgmtProjectTransferControllerTest {
    private final ProjectArchiveService service = mock(ProjectArchiveService.class);
    private final MockMvc mvc = createMvc();

    /**
     * 建立文件接口并注册真实 JSON 解析与全局错误映射，不启动应用数据库。
     * @return 隔离接口测试入口
     */
    private MockMvc createMvc() {
        var controller = new MgmtProjectTransferController();
        ReflectionTestUtils.setField(controller, "archiveService", service);
        ReflectionTestUtils.setField(controller, "archiveFormat", new ProjectArchiveFormat());
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    /** 导出响应提供可下载文件且不缓存项目资料。 */
    @Test
    void shouldDownloadJsonAttachmentWithoutCaching() throws Exception {
        when(service.exportProject(12)).thenReturn("{\"version\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/project-transfers/12")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=project-12.json"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.version").value(1));
    }

    /** 导入创建新项目并返回其 ID 和各分区数量。 */
    @Test
    void shouldReturnCreatedProjectAndCounts() throws Exception {
        when(service.importProject(any())).thenReturn(new ProjectImportResponse("21", "迁移项目", Map.of("tasks", 3)));
        mvc.perform(post("/project-transfers").contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.projectId").value("21"))
                .andExpect(jsonPath("$.counts.tasks").value(3));
    }

    /** 无效 JSON 在调用事务服务之前拒绝，缺失渠道的业务原因可在页面展示。 */
    @Test
    void shouldRejectInvalidJsonAndReturnReadableMissingChannelError() throws Exception {
        mvc.perform(post("/project-transfers").contentType("application/json").content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("项目文件不是有效的 JSON"));
        verifyNoInteractions(service);
        when(service.importProject(any())).thenThrow(new IllegalArgumentException("请先配置渠道编码：TEST"));
        mvc.perform(post("/project-transfers").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("请先配置渠道编码：TEST"));
    }

    /** 数据库失败响应不包含 SQL 异常中的文件正文或认证资料。 */
    @Test
    void shouldNotExposeDatabaseExceptionPayload() throws Exception {
        when(service.importProject(any())).thenThrow(new DataIntegrityViolationException("SQL payload must stay private"));
        mvc.perform(post("/project-transfers").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("项目文件的字段、关联或重复记录不符合要求，导入已回滚"));
    }
}
