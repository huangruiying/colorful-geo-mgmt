package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.project.ProjectBusiness;
import org.huangry.colorful.geo.application.project.model.ProjectInfoPage;
import org.huangry.colorful.geo.application.project.model.ProjectInfoResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 项目 HTTP 契约回归；创建返回 201 与标识，查询采用零起始分页，输入错误返回 400。 */
class ProjectControllerTest {
    private final ProjectBusiness business = mock(ProjectBusiness.class);
    private final MockMvc mvc = createMvc();

    /** 单独构造接口并注入业务替身，不启动数据库。 */
    private MockMvc createMvc() {
        var controller = new MgmtProjectInfoController();
        ReflectionTestUtils.setField(controller, "projectBusiness", business);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 成功创建返回可用于任务关联的项目 ID。 */
    @Test
    void shouldReturnCreatedProjectId() throws Exception {
        ProjectInfoResponse project = new ProjectInfoResponse(); project.setId(8L); project.setName("产品");
        when(business.createProject(any())).thenReturn(project);
        mvc.perform(post("/projects").contentType("application/json").content("{\"name\":\"产品\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(8));
    }

    /** 查询默认条件和业务参数错误都有稳定响应。 */
    @Test
    void shouldReturnPageAndReadableValidationError() throws Exception {
        when(business.listProjects(null, 0, 20)).thenReturn(new ProjectInfoPage(List.of(), 0, 0, 20));
        mvc.perform(get("/projects")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        when(business.createProject(any())).thenThrow(new IllegalArgumentException("请填写项目名称"));
        mvc.perform(post("/projects").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("请填写项目名称"));
    }
}
