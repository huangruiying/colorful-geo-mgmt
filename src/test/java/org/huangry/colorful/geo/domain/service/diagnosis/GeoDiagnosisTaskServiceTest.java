package org.huangry.colorful.geo.domain.service.diagnosis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.alibaba.fastjson.JSON;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.huangry.colorful.geo.application.diagnosis.model.CreateDiagnosisTaskRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter;
import org.huangry.colorful.geo.infrastructure.repository.*;
import org.huangry.colorful.geo.infrastructure.repository.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 创建诊断结果与配置冻结测试；事务原子性、状态流转另用真实 MySQL/API 验证。 */
class GeoDiagnosisTaskServiceTest {
    /** 非成功明细按任务隔离清空，成功状态排除，主任务同时回到待执行。 */
    @Test
    void shouldRestartOnlyNonSuccessfulResultsOfFinishedTask() {
        var task = new GeoDiagnosisPlanExecRecordEntity();
        task.setId(27L); task.setStatus("PARTIAL_FAILED");
        when(tasks.selectOne(any())).thenReturn(task);
        when(results.update(isNull(), any())).thenAnswer(invocation -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>) invocation.getArgument(1);
            String where = update.getSqlSegment();
            assertTrue(where.contains("exec_record_id ="));
            assertTrue(where.contains("status <>"));
            assertTrue(update.getParamNameValuePairs().containsValue(27L));
            assertTrue(update.getParamNameValuePairs().containsValue("SUCCEEDED"));
            assertTrue(update.getParamNameValuePairs().containsValue("PENDING"));
            assertTrue(update.getSqlSet().contains("error_message="));
            assertTrue(update.getSqlSet().contains("raw_response="));
            assertTrue(update.getSqlSet().contains("finished_at="));
            assertFalse(update.getSqlSet().contains("channel_snapshot="));
            return 2;
        });
        when(tasks.update(isNull(), any())).thenReturn(1);
        service.restart(27L);
        verify(tasks).update(isNull(), any());
        verify(results).update(isNull(), any());
    }

    /** 活动任务、成功任务不能重新排队，避免重复调用和覆盖成功结果。 */
    @Test
    void shouldRejectRestartOfActiveOrSuccessfulTask() {
        for (String status : List.of("PENDING", "RUNNING", "SUCCEEDED")) {
            var task = new GeoDiagnosisPlanExecRecordEntity();
            task.setId(27L); task.setStatus(status);
            when(tasks.selectOne(any())).thenReturn(task);
            assertThrows(IllegalArgumentException.class, () -> service.restart(27L));
        }
        verify(results, never()).update(isNull(), any());
        verify(tasks, never()).update(isNull(), any());
    }

    /** 缺失任务不写明细，已结束但无可重试结果也不能变成空排队任务。 */
    @Test
    void shouldRejectMissingTaskAndEmptyRestart() {
        when(tasks.selectOne(any())).thenReturn(null);
        assertThrows(NoSuchElementException.class, () -> service.restart(999L));
        var task = new GeoDiagnosisPlanExecRecordEntity();
        task.setId(27L); task.setStatus("FAILED");
        when(tasks.selectOne(any())).thenReturn(task);
        when(results.update(isNull(), any())).thenReturn(0);
        assertThrows(IllegalArgumentException.class, () -> service.restart(27L));
        verify(tasks, never()).update(isNull(), any());
    }

    private final GeoDiagnosisTaskService service = new GeoDiagnosisTaskService();
    private final GeoDiagnosisPlanExecRecordDao tasks = mock(GeoDiagnosisPlanExecRecordDao.class);
    private final GeoDiagnosisPlanExecRecordResultDao results = mock(GeoDiagnosisPlanExecRecordResultDao.class);
    private final GeoDiagnosisPlanDao plans = mock(GeoDiagnosisPlanDao.class);
    private final GeoProjectInfoDao projects = mock(GeoProjectInfoDao.class);
    private final GeoLlmChannelDao channels = mock(GeoLlmChannelDao.class);
    private final GeoDiagnosisPlanService planService = mock(GeoDiagnosisPlanService.class);
    private final LLMStrategyRouter router = mock(LLMStrategyRouter.class);
    private final DiagnosisTaskSnapshots snapshots = new DiagnosisTaskSnapshots();

    private final List<GeoDiagnosisPlanExecRecordResultEntity> inserted = new ArrayList<>();
    private final List<Integer> batchSizes = new ArrayList<>();
    private final AtomicReference<GeoDiagnosisPlanExecRecordEntity> saved = new AtomicReference<>();

    /** 注入真实快照转换与模拟数据库，诊断结果批次按调用时复制避免列表清空影响断言。 */
    @BeforeEach
    void setup() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "diagnosis-task-test");
        for (Class<?> type : List.of(GeoDiagnosisPlanEntity.class, GeoDiagnosisPlanExecRecordEntity.class,
                GeoDiagnosisPlanExecRecordResultEntity.class, GeoProjectInfoEntity.class, GeoLlmChannelEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }

        ReflectionTestUtils.setField(snapshots, "strategies", Map.of("mockLlmStrategy",
                new org.huangry.colorful.geo.infrastructure.client.llm.strategy.MockLlmStrategy()));
        ReflectionTestUtils.setField(service, "taskDao", tasks);
        ReflectionTestUtils.setField(service, "resultDao", results);
        ReflectionTestUtils.setField(service, "planDao", plans);
        ReflectionTestUtils.setField(service, "projectDao", projects);
        ReflectionTestUtils.setField(service, "channelDao", channels);
        ReflectionTestUtils.setField(service, "planService", planService);
        ReflectionTestUtils.setField(service, "router", router);
        ReflectionTestUtils.setField(service, "snapshots", snapshots);

        when(tasks.insert(any(GeoDiagnosisPlanExecRecordEntity.class))).thenAnswer(call -> {
            var task = call.<GeoDiagnosisPlanExecRecordEntity>getArgument(0);
            task.setId(55L); saved.set(task); return 1;
        });
        when(tasks.selectById(55L)).thenAnswer(call -> saved.get());
        when(results.insertSamples(anyList())).thenAnswer(call -> {
            var batch = call.<List<GeoDiagnosisPlanExecRecordResultEntity>>getArgument(0);
            batchSizes.add(batch.size()); inserted.addAll(batch); return batch.size();
        });
    }

    /** 诊断结果数量、问题输入和凭据隔离共同验证，不仅检查方法调用次数。 */
    @Test
    void shouldFreezeAllIndependentSamplesWithoutCredentials() throws Exception {
        configure(2, 2, 3);
        var task = service.create(new CreateDiagnosisTaskRequest(1L, "create-test"));
        assertEquals(12, inserted.size());
        assertEquals("PENDING", task.getStatus());
        assertTrue(inserted.stream().allMatch(row -> "PENDING".equals(row.getStatus())));
        assertEquals(List.of(12), batchSizes);
        assertEquals(12, inserted.stream().map(row -> row.getQuestionSortOrder() + ":" + row.getChannelId() + ":" + row.getRepeatIndex()).distinct().count());
        assertTrue(inserted.stream().allMatch(row -> row.getExecRecordId().equals(55L)));
        assertTrue(inserted.stream().allMatch(row -> row.getQuestionSnapshot().contains("独立问题") && !row.getQuestionSnapshot().contains("项目背景")));
        assertFalse(task.getPlanSnapshot().contains("qa-secret"));
        assertFalse(task.getPlanSnapshot().contains("private-query"));
        assertFalse(task.getPlanSnapshot().contains("apiKey"));
        assertEquals("https://example.invalid/v1", JSON.parseObject(task.getPlanSnapshot()).getJSONArray("channels").getJSONObject(0).getString("baseUrl"));
        assertEquals("MOCK", JSON.parseObject(task.getPlanSnapshot()).getJSONArray("channels").getJSONObject(0).getString("protocolType"));
        assertEquals(2, JSON.parseObject(task.getPlanSnapshot()).getJSONArray("questions").size());
        assertEquals("项目背景", JSON.parseObject(task.getPlanSnapshot()).getJSONObject("project").getString("coreFeatures"));
    }

    /** 最大诊断结果规模保持固定批次，不退化成四万次单条插入。 */
    @Test
    void shouldInitializeMaximumScaleInBoundedBatches() {
        configure(200, 20, 10);
        service.create(new CreateDiagnosisTaskRequest(1L, "maximum-scale"));
        assertEquals(40000, inserted.size());
        assertEquals(160, batchSizes.size());
        assertTrue(batchSizes.stream().allMatch(size -> size == 250));
    }

    /** 已创建提交不再次生成诊断结果，配置修改不改变原任务。 */
    @Test
    void shouldReuseCreatedSubmissionAndRejectDifferentPlan() {
        configure(2, 2, 2);
        var original = new GeoDiagnosisPlanExecRecordEntity(); original.setId(77L); original.setPlanId(1L);
        when(tasks.selectOne(any())).thenReturn(original);
        assertSame(original, service.create(new CreateDiagnosisTaskRequest(1L, "existing")));
        verify(tasks, never()).insert(any(GeoDiagnosisPlanExecRecordEntity.class));
        verifyNoInteractions(results, projects);
        assertThrows(IllegalArgumentException.class, () -> service.requireSamePlan(original, 2L));
    }

    /** 无效提交在读取配置前拒绝，避免意外创建任务。 */
    @Test
    void shouldRejectInvalidSubmissionBeforeWrite() {
        for (var request : List.of(new CreateDiagnosisTaskRequest(null, "a"),
                new CreateDiagnosisTaskRequest(1L, ""), new CreateDiagnosisTaskRequest(1L, "含中文"),
                new CreateDiagnosisTaskRequest(1L, "x".repeat(65)))) {
            assertThrows(IllegalArgumentException.class, () -> service.create(request));
        }
        verifyNoInteractions(tasks, results, plans);
    }

    /** 密钥轮换允许继续，但改变生成参数必须拒绝，避免混用诊断结果条件。 */
    @Test
    void shouldAllowCredentialRotationButRejectChangedConfiguration() {
        var channel = channel(1L);
        var sample = new GeoDiagnosisPlanExecRecordResultEntity();
        sample.setChannelId(1L); sample.setChannelSnapshot(JSON.toJSONString(snapshots.channel(channel),
                com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));
        when(channels.selectById(1L)).thenReturn(channel);
        channel.setApiKey("rotated-secret");
        assertSame(channel, service.prepareChannel(sample));
        channel.setMaxTokens(999);
        assertThrows(IllegalArgumentException.class, () -> service.prepareChannel(sample));
        verifyNoInteractions(results);
    }

    /** 历史快照含显式空值且小数位数不同时，不应因 JSON 实现变化阻断同一渠道。 */
    @Test
    void shouldReadHistoricalSnapshotWithNullsAndEquivalentDecimals() {
        var channel = channel(1L);
        channel.setTemperature(0.3);
        var frozen = JSON.toJSONString(snapshots.channel(channel),
                com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue).replace("0.3", "0.30");
        var sample = new GeoDiagnosisPlanExecRecordResultEntity();
        sample.setChannelId(1L);
        sample.setChannelSnapshot(frozen);
        when(channels.selectById(1L)).thenReturn(channel);
        assertSame(channel, service.prepareChannel(sample));
        channel.setTemperature(null);
        assertThrows(IllegalArgumentException.class, () -> service.prepareChannel(sample));
    }

    /** 引用是 Java record，落库后必须仍含链接和缺失位置，不能退化为空对象。 */
    @Test
    void shouldPersistCitationRecordFieldsAndNullPositions() {
        var citation = new org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation(
                "来源", "https://example.invalid/source", null, null, "摘要");
        var response = new org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse("回答", List.of(citation));
        var sample = new GeoDiagnosisPlanExecRecordResultEntity();
        sample.setId(9L);
        when(results.update(any(), any())).thenReturn(1);
        service.succeed(sample, response, 20);
        var savedCitation = JSON.parseArray(sample.getCitations()).getJSONObject(0);
        assertEquals(citation.url(), savedCitation.getString("url"));
        assertEquals(citation.title(), savedCitation.getString("title"));
        assertEquals(citation.snippet(), savedCitation.getString("snippet"));
        assertTrue(savedCitation.containsKey("startIndex"));
        assertNull(savedCitation.get("startIndex"));
    }

    /** 汇总使用整轮枚举，但写入数据库的仍是原有三个终态字符串。 */
    @Test
    void shouldKeepTaskTerminalStatusValuesWhenAggregatingResults() {
        List<List<Map<String, Object>>> counts = List.of(
                List.of(Map.of("status", "SUCCEEDED", "sample_count", 2L)),
                List.of(Map.of("status", "FAILED", "sample_count", 2L)),
                List.of(Map.of("status", "SUCCEEDED", "sample_count", 1L),
                        Map.of("status", "FAILED", "sample_count", 1L)));
        List<String> expected = List.of("SUCCEEDED", "FAILED", "PARTIAL_FAILED");
        for (int index = 0; index < counts.size(); index++) {
            clearInvocations(tasks);
            when(results.countStatuses(55L)).thenReturn(counts.get(index));
            service.finish(55L);
            var wrapper = org.mockito.ArgumentCaptor.forClass(
                    com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
            verify(tasks).update(isNull(), wrapper.capture());
            wrapper.getValue().getSqlSet();
            wrapper.getValue().getSqlSegment();
            assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(expected.get(index)));
            assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue("RUNNING"));
        }
    }

    /** 仍有未完成的诊断时不能因为更换状态表达方式而提前结束整轮任务。 */
    @Test
    void shouldRejectFinishingTaskWithPendingOrRunningSamples() {
        for (String status : List.of("PENDING", "RUNNING")) {
            when(results.countStatuses(55L)).thenReturn(List.of(Map.of("status", status, "sample_count", 1L)));
            assertThrows(IllegalStateException.class, () -> service.finish(55L));
        }
        verify(tasks, never()).update(any(), any());
    }

    /** 真实 MyBatis 动态 SQL 必须把初始状态作为参数绑定，不能在批量插入时丢失。 */
    @Test
    void shouldBindInitialSampleStatusInBatchInsert() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(GeoDiagnosisPlanExecRecordResultDao.class);
        var sample = new GeoDiagnosisPlanExecRecordResultEntity();
        sample.setStatus("PENDING");
        var statement = configuration.getMappedStatement(GeoDiagnosisPlanExecRecordResultDao.class.getName() + ".insertSamples");
        var sql = statement.getBoundSql(Map.of("items", List.of(sample)));
        var statusParameter = sql.getParameterMappings().stream()
                .filter(parameter -> parameter.getProperty().endsWith(".status")).findFirst().orElseThrow();
        Object value = configuration.newMetaObject(sql.getAdditionalParameters()).getValue(statusParameter.getProperty());
        assertEquals("PENDING", value);
        assertFalse(sql.getSql().contains("'PENDING'"));
        assertEquals(8, sql.getParameterMappings().size());
    }

    /**
     * 生成规模明确的输入，渠道读取按保存顺序返回。
     * @param questionCount 问题数量
     * @param channelCount 渠道数量
     * @param repeat 重复执行次数
     */
    private void configure(int questionCount, int channelCount, int repeat) {
        var plan = new GeoDiagnosisPlanEntity(); plan.setId(1L); plan.setProjectId(2L); plan.setName("方案"); plan.setRepeatCount(repeat);
        when(plans.selectOne(any())).thenReturn(plan);
        var project = new GeoProjectInfoEntity(); project.setId(2L); project.setName("项目"); project.setCoreFeatures("项目背景");
        when(projects.selectOne(any())).thenReturn(project);
        List<GeoDiagnosisPlanQuestionEntity> questions = new ArrayList<>();
        for (int index = 0; index < questionCount; index++) {
            var question = new GeoDiagnosisPlanQuestionEntity(); question.setId(index + 1L); question.setSortOrder(index);
            question.setQuestionText("独立问题 " + index); questions.add(question);
        }
        when(planService.listQuestions(1L)).thenReturn(questions);
        List<GeoDiagnosisPlanChannelEntity> selected = new ArrayList<>();
        List<GeoLlmChannelEntity> models = new ArrayList<>();
        for (int index = 0; index < channelCount; index++) {
            var selection = new GeoDiagnosisPlanChannelEntity(); selection.setChannelId(index + 1L); selected.add(selection);
            models.add(channel(index + 1L));
        }
        when(planService.listChannels(1L)).thenReturn(selected);
        var next = models.iterator();
        when(channels.selectOne(any())).thenAnswer(call -> next.next());
    }

    /**
     * 测试地址故意带认证与查询串，确保快照不会泄露。
     * @param id 渠道 ID
     * @return 配置样本
     */
    private GeoLlmChannelEntity channel(long id) {
        var channel = new GeoLlmChannelEntity(); channel.setId(id); channel.setEnabled(true);
        channel.setChannelCode("fixture-" + id); channel.setChannelName("渠道 " + id);
        channel.setStrategyBeanName("mockLlmStrategy"); channel.setModelName("fixture-model");
        channel.setApiKey("qa-secret"); channel.setBaseUrl("https://qa-secret@example.invalid/v1?token=private-query");
        channel.setMaxTokens(100); return channel;
    }
}
