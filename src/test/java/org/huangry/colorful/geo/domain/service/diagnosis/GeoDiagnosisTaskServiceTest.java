package org.huangry.colorful.geo.domain.service.diagnosis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.alibaba.fastjson.JSON;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.huangry.colorful.geo.application.diagnosis.model.CreateDiagnosisTaskRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.LLMStrategyRouter;
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

/** 创建采样与配置冻结测试；事务原子性、状态流转另用真实 MySQL/API 验证。 */
class GeoDiagnosisTaskServiceTest {
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

    /** 注入真实快照转换与模拟数据库，采样批次按调用时复制避免列表清空影响断言。 */
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

    /** 采样数量、问题输入和凭据隔离共同验证，不仅检查方法调用次数。 */
    @Test
    void shouldFreezeAllIndependentSamplesWithoutCredentials() throws Exception {
        configure(2, 2, 3);
        var task = service.create(new CreateDiagnosisTaskRequest(1L, "create-test"));
        assertEquals(12, inserted.size());
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

    /** 最大采样规模保持固定批次，不退化成四万次单条插入。 */
    @Test
    void shouldInitializeMaximumScaleInBoundedBatches() {
        configure(200, 20, 10);
        service.create(new CreateDiagnosisTaskRequest(1L, "maximum-scale"));
        assertEquals(40000, inserted.size());
        assertEquals(160, batchSizes.size());
        assertTrue(batchSizes.stream().allMatch(size -> size == 250));
    }

    /** 已创建提交不再次生成采样，配置修改不改变原任务。 */
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

    /** 密钥轮换允许继续，但改变生成参数必须拒绝，避免混用采样条件。 */
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

    /**
     * 生成规模明确的输入，渠道读取按保存顺序返回。
     * @param questionCount 问题数量
     * @param channelCount 渠道数量
     * @param repeat 重复次数
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
