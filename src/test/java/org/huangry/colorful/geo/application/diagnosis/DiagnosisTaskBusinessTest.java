package org.huangry.colorful.geo.application.diagnosis;

import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskResultDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证整轮任务与单条诊断结果分别校验状态，并保持接口和数据损坏处理语义。 */
class DiagnosisTaskBusinessTest {
    private final DiagnosisTaskBusiness business = new DiagnosisTaskBusiness();
    private final GeoDiagnosisTaskDao tasks = mock(GeoDiagnosisTaskDao.class);
    private final GeoDiagnosisTaskResultDao results = mock(GeoDiagnosisTaskResultDao.class);

    /** 注入查询替身及真实快照解析，不连接数据库或调用模型。 */
    @BeforeEach
    void setup() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "diagnosis-status-test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, GeoDiagnosisTaskEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity.class);
        ReflectionTestUtils.setField(business, "taskDao", tasks);
        ReflectionTestUtils.setField(business, "resultDao", results);
        ReflectionTestUtils.setField(business, "snapshots", new DiagnosisTaskSnapshots());
    }

    /** 部分失败只允许筛选整轮任务，不能作为单条诊断结果筛选值。 */
    @Test
    void shouldAllowPartialFailureOnlyForTaskFilter() {
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectList(any())).thenReturn(List.of());
        assertEquals(0L, business.list(null, "PARTIAL_FAILED", 0, 20).total());
        when(tasks.selectById(1L)).thenReturn(task());
        assertThrows(IllegalArgumentException.class, () -> business.results(1L, "PARTIAL_FAILED", null, 0, 20));
        verifyNoInteractions(results);
    }

    /** 可选筛选允许空白，未知或大小写不一致的状态仍拒绝。 */
    @Test
    void shouldKeepOptionalAndInvalidStatusFilterBehavior() {
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectList(any())).thenReturn(List.of());
        assertDoesNotThrow(() -> business.list(null, null, 0, 20));
        assertDoesNotThrow(() -> business.list(null, " ", 0, 20));
        for (String status : List.of("UNKNOWN", "pending", " PENDING ")) {
            var exception = assertThrows(IllegalArgumentException.class, () -> business.list(null, status, 0, 20));
            assertEquals("诊断状态筛选无效", exception.getMessage());
        }
    }

    /** 诊断结果聚合按枚举分类，接口继续输出既有字符串状态和各状态计数。 */
    @Test
    void shouldAggregateResultStatusesAndKeepResponseStatusString() {
        when(tasks.selectById(1L)).thenReturn(task());
        when(results.countStatuses(1L)).thenReturn(List.of(
                Map.of("channel_id", 5L, "status", "PENDING", "sample_count", 1L),
                Map.of("channel_id", 5L, "status", "RUNNING", "sample_count", 2L),
                Map.of("channel_id", 5L, "status", "SUCCEEDED", "sample_count", 3L),
                Map.of("channel_id", 5L, "status", "FAILED", "sample_count", 4L)));
        var response = business.detail(1L);
        assertEquals("RUNNING", response.status());
        assertEquals(10L, response.progress().total());
        assertEquals(1L, response.progress().pending());
        assertEquals(2L, response.progress().running());
        assertEquals(3L, response.progress().succeeded());
        assertEquals(4L, response.progress().failed());
    }

    /** 数据库未知诊断结果状态仍按状态损坏失败，不误报为请求参数错误。 */
    @Test
    void shouldRejectUnknownPersistedResultStatusAsCorruption() {
        when(tasks.selectById(1L)).thenReturn(task());
        when(results.countStatuses(1L)).thenReturn(List.of(
                Map.of("channel_id", 5L, "status", "PARTIAL_FAILED", "sample_count", 1L)));
        var exception = assertThrows(IllegalStateException.class, () -> business.detail(1L));
        assertEquals("诊断状态损坏", exception.getMessage());
    }

    /**
     * 创建供响应组装使用的任务，状态字段保持数据库的字符串类型。
     * @return 最小完整的任务快照
     */
    private GeoDiagnosisTaskEntity task() {
        var task = new GeoDiagnosisTaskEntity();
        task.setId(1L);
        task.setStatus("RUNNING");
        task.setPlanSnapshot("{\"planName\":\"方案\",\"project\":{\"name\":\"项目\"},\"channels\":[]}");
        return task;
    }
}
