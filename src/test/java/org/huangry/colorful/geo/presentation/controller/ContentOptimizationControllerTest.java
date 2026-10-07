package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.content.ContentOptimizationBusiness;
import org.huangry.colorful.geo.application.content.model.ContentOptimizationRecordPage;
import org.huangry.colorful.geo.application.content.model.ContentOptimizationRecordDetail;
import org.huangry.colorful.geo.domain.model.ContentOptimizationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内容优化接口路由测试；确保页面使用的新路径能到达对应业务入口。
 */
class ContentOptimizationControllerTest {

    private ContentOptimizationBusiness optimizationBusiness;
    private MockMvc mockMvc;

    /** 每个用例独立创建优化业务替身，不调用大模型或数据库。 */
    @BeforeEach
    void setUp() {
        optimizationBusiness = mock(ContentOptimizationBusiness.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ContentOptimizationController(optimizationBusiness)).build();
    }

    /** 新建优化使用当前优化路径。 */
    @Test
    void 新建优化使用当前路径() throws Exception {
        when(optimizationBusiness.optimizeAndSave(any()))
                .thenReturn(ContentOptimizationRecord.builder().id(7L).build());
        mockMvc.perform(post("/content-optimize/optimize")
                        .contentType("application/json")
                        .content("{\"title\":\"标题\",\"content\":\"正文\"}"))
                .andExpect(status().isCreated());

        verify(optimizationBusiness).optimizeAndSave(any());
    }

    /** 列表路径保留分页参数。 */
    @Test
    void 优化列表使用当前路径() throws Exception {
        when(optimizationBusiness.listContentOptimizationRecords(0, 20))
                .thenReturn(new ContentOptimizationRecordPage(List.of(), 0, 0, 20));

        mockMvc.perform(get("/content-optimize/list"))
                .andExpect(status().isOk());

        verify(optimizationBusiness).listContentOptimizationRecords(0, 20);
    }

    /** 详情路径的 ID 是优化记录 ID。 */
    @Test
    void 优化详情使用当前路径() throws Exception {
        when(optimizationBusiness.getContentOptimizationRecord(7L))
                .thenReturn(new ContentOptimizationRecordDetail(
                        ContentOptimizationRecord.builder().id(7L).build(), List.of()));
        mockMvc.perform(get("/content-optimize/detail/7"))
                .andExpect(status().isOk());

        verify(optimizationBusiness).getContentOptimizationRecord(7L);
    }
}
