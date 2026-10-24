package org.huangry.colorful.geo.domain.service.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.huangry.colorful.geo.infrastructure.repository.ProjectArchiveRepository;
import org.huangry.colorful.geo.infrastructure.repository.ProjectArchiveTable;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static org.huangry.colorful.geo.infrastructure.repository.ProjectArchiveTable.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用生产 DDL 转换出的隔离 H2 表验证完整迁移及真实事务回滚；不代表 MySQL 或模型平台联调。 */
class ProjectArchiveServiceTest {
    private final ProjectArchiveFormat format = new ProjectArchiveFormat();

    /** 完整迁移后全部外键、快照与报告证据指向新记录；渠道按编码匹配且凭据不出库。 */
    @Test
    void shouldRoundTripAllTablesAndRemapSnapshotsWithoutChannelCredentials() throws Exception {
        Fixture source = createFixture();
        seedProject(source, true);
        byte[] exported = source.service.exportProject(10);
        String json = new String(exported, StandardCharsets.UTF_8);
        assertFalse(json.contains("source-secret"));
        assertFalse(json.contains("must-not-leak"));
        assertFalse(json.contains("baseUrl"));
        assertFalse(json.contains("strategyBeanName"));
        assertFalse(json.contains("channel_id"));
        assertFalse(json.contains("source-request"));
        Fixture target = createFixture();
        target.jdbc.update("UPDATE geo_llm_channel SET id = 700 WHERE id = 7");
        ProjectImportResponse imported = target.service.importProject(format.parseArchive(exported));
        long projectId = Long.parseLong(imported.projectId());
        assertEquals(1, imported.counts().get("analyses"));
        assertEquals(projectId, target.jdbc.queryForObject("SELECT project_id FROM geo_diagnosis_task", Long.class));
        assertEquals(700, target.jdbc.queryForObject("SELECT channel_id FROM geo_diagnosis_task_result", Long.class));
        assertNull(target.jdbc.queryForObject("SELECT request_id FROM geo_diagnosis_task", String.class));
        long resultId = target.jdbc.queryForObject("SELECT id FROM geo_diagnosis_task_result", Long.class);
        assertEquals(resultId, target.jdbc.queryForObject("SELECT task_result_id FROM geo_diagnosis_task_result_analysis", Long.class));
        JsonNode snapshot = parseStored(target, "geo_diagnosis_task", "plan_snapshot");
        assertEquals(projectId, snapshot.path("project").path("id").asLong());
        assertEquals(700, snapshot.path("channels").get(0).path("id").asLong());
        JsonNode summary = parseStored(target, "geo_diagnosis_task_report", "summary");
        assertEquals(resultId, summary.path("evidence").get(0).path("resultId").asLong());
        assertEquals(700, summary.path("evidence").get(0).path("channelId").asLong());
        assertTrue(summary.path("issues").get(0).path("channelId").isNull());
        assertEquals("P1", summary.path("issues").get(0).path("id").asText());
        for (ProjectArchiveTable table : values()) {
            assertEquals(1, target.jdbc.queryForObject("SELECT COUNT(*) FROM " + table.table, Integer.class), table.section);
        }
        assertEquals("FAILED", target.jdbc.queryForObject("SELECT status FROM geo_diagnosis_task", String.class));
        assertEquals("SUCCEEDED", target.jdbc.queryForObject("SELECT status FROM geo_diagnosis_task_report", String.class));
        // 再次导出导入后的项目也必须满足文件契约，重复导入会生成第二份项目
        target.service.exportProject(projectId);
        target.service.importProject(format.parseArchive(exported));
        assertEquals(2, target.jdbc.queryForObject("SELECT COUNT(*) FROM geo_project_info", Integer.class));
    }

    /** 已从当前方案删除的问题只恢复追溯标识，不作为新的当前问题出现。 */
    @Test
    void shouldReserveFreshHistoricalQuestionIdWithoutChangingCurrentPlan() throws Exception {
        Fixture source = createFixture();
        seedProject(source, true);
        source.jdbc.update("DELETE FROM geo_diagnosis_plan_question WHERE id = 30");
        Fixture target = createFixture();
        target.service.importProject(format.parseArchive(source.service.exportProject(10)));
        assertEquals(0, target.jdbc.queryForObject("SELECT COUNT(*) FROM geo_diagnosis_plan_question", Integer.class));
        long questionId = target.jdbc.queryForObject("SELECT question_id FROM geo_diagnosis_task_result", Long.class);
        assertNotEquals(30, questionId);
        assertEquals(questionId, parseStored(target, "geo_diagnosis_task_result", "question_snapshot").path("id").asLong());
    }

    /** 缺失渠道应一次提示全部编码，并且不留下任何项目数据。 */
    @Test
    void shouldRejectMissingChannelsBeforeWriting() throws Exception {
        Fixture source = createFixture();
        seedProject(source, true);
        ObjectNode archive = format.parseArchive(source.service.exportProject(10));
        ((ObjectNode) archive.withArray(PLAN_CHANNEL.section).get(0)).put("channel_code", "MISSING_A");
        ((ObjectNode) archive.withArray(REPORT.section).get(0).get("judge_channel_snapshot")).put("channelCode", "MISSING_B");
        Fixture target = createFixture();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> target.service.importProject(archive));
        assertTrue(error.getMessage().contains("MISSING_A"));
        assertTrue(error.getMessage().contains("MISSING_B"));
        assertEquals(0, target.jdbc.queryForObject("SELECT COUNT(*) FROM geo_project_info", Integer.class));
    }

    /** 最后一张表违反字段约束时，前面七张表写入也必须全部回滚。 */
    @Test
    void shouldRollBackEveryTableWhenLastInsertFails() throws Exception {
        Fixture source = createFixture();
        seedProject(source, true);
        ObjectNode archive = format.parseArchive(source.service.exportProject(10));
        ((ObjectNode) archive.withArray(ANALYSIS.section).get(0)).put("brand_sentiment", "INVALID");
        Fixture target = createFixture();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> target.service.importProject(archive));
        for (ProjectArchiveTable table : values()) {
            assertEquals(0, target.jdbc.queryForObject("SELECT COUNT(*) FROM " + table.table, Integer.class), table.section);
        }
    }

    /** 待执行及执行中的任务、报告都不能导出，篡改文件也不能绕过导入终态检查。 */
    @Test
    void shouldRejectActiveTasksReportsAndTamperedStatus() throws Exception {
        Fixture source = createFixture();
        seedProject(source, true);
        ObjectNode archive = format.parseArchive(source.service.exportProject(10));
        for (String state : List.of("PENDING", "RUNNING")) {
            source.jdbc.update("UPDATE geo_diagnosis_task SET status = ?", state);
            assertThrows(IllegalArgumentException.class, () -> source.service.exportProject(10));
            source.jdbc.update("UPDATE geo_diagnosis_task SET status = 'FAILED'");
            source.jdbc.update("UPDATE geo_diagnosis_task_report SET status = ?", state);
            assertThrows(IllegalArgumentException.class, () -> source.service.exportProject(10));
            source.jdbc.update("UPDATE geo_diagnosis_task_report SET status = 'SUCCEEDED'");
        }
        ((ObjectNode) archive.withArray(TASK.section).get(0)).put("status", "PENDING");
        assertThrows(IllegalArgumentException.class, () -> source.service.importProject(archive));
    }

    /** 不含诊断数据的基础项目仍可独立迁移。 */
    @Test
    void shouldTransferProjectWithoutDiagnosisData() throws Exception {
        Fixture source = createFixture();
        seedProject(source, false);
        Fixture target = createFixture();
        ProjectImportResponse result = target.service.importProject(format.parseArchive(source.service.exportProject(10)));
        assertEquals(0, result.counts().get("tasks"));
        assertEquals(1, target.jdbc.queryForObject("SELECT COUNT(*) FROM geo_project_info", Integer.class));
    }

    /** 重复 ID、悬空关联、跨任务分析与无效文件不得被悄悄忽略。 */
    @Test
    void shouldRejectInvalidVersionDuplicateIdsAndDanglingReferences() throws Exception {
        Fixture source = createFixture();
        seedProject(source, true);
        byte[] bytes = source.service.exportProject(10);
        ObjectNode version = format.parseArchive(bytes);
        version.put("version", 2);
        assertThrows(IllegalArgumentException.class, () -> source.service.importProject(version));
        ObjectNode duplicate = format.parseArchive(bytes);
        duplicate.withArray(PLAN.section).add(duplicate.withArray(PLAN.section).get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> source.service.importProject(duplicate));
        ObjectNode dangling = format.parseArchive(bytes);
        ((ObjectNode) dangling.withArray(RESULT.section).get(0)).put("task_id", "999");
        assertThrows(IllegalArgumentException.class, () -> source.service.importProject(dangling));
        assertThrows(IllegalArgumentException.class, () -> format.parseArchive("{\"version\":1,\"version\":2}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> format.parseArchive("{} {}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> format.parseArchive(new byte[0]));
    }

    /** 超过 JavaScript 精确整数范围的来源标识以字符串传输，避免错误关联。 */
    @Test
    void shouldPreserveLargeIdsAsStrings() throws Exception {
        Fixture source = createFixture();
        seedProject(source, false);
        long id = 9007199254740993L;
        source.jdbc.update("UPDATE geo_project_info SET id = ? WHERE id = 10", id);
        ObjectNode archive = format.parseArchive(source.service.exportProject(id));
        assertTrue(archive.withArray(PROJECT.section).get(0).path("id").isTextual());
        assertEquals(Long.toString(id), archive.withArray(PROJECT.section).get(0).path("id").asText());
    }

    /** 快照与行外键不一致、或渠道快照夹带认证字段时，在写入之前拒绝文件。 */
    @Test
    void shouldRejectMismatchedSnapshotsAndInjectedCredentials() throws Exception {
        Fixture source = createFixture();
        seedProject(source, true);
        byte[] bytes = source.service.exportProject(10);
        Fixture target = createFixture();
        ObjectNode mismatch = format.parseArchive(bytes);
        ((ObjectNode) mismatch.withArray(TASK.section).get(0).path("plan_snapshot").path("project")).put("id", "999");
        assertThrows(IllegalArgumentException.class, () -> target.service.importProject(mismatch));
        ObjectNode injected = format.parseArchive(bytes);
        ((ObjectNode) injected.withArray(RESULT.section).get(0).path("channel_snapshot")).put("apiKey", "synthetic-secret");
        assertThrows(IllegalArgumentException.class, () -> target.service.importProject(injected));
        assertEquals(0, target.jdbc.queryForObject("SELECT COUNT(*) FROM geo_project_info", Integer.class));
    }

    /** 报告证据指向文件外结果时，禁止保留来源 ID，并回滚已插入的父表。 */
    @Test
    void shouldRollBackWhenReportEvidenceReferencesMissingResult() throws Exception {
        Fixture source = createFixture();
        seedProject(source, true);
        ObjectNode archive = format.parseArchive(source.service.exportProject(10));
        ((ObjectNode) archive.withArray(REPORT.section).get(0).path("summary").path("evidence").get(0)).put("resultId", "999");
        Fixture target = createFixture();
        assertThrows(IllegalArgumentException.class, () -> target.service.importProject(archive));
        for (ProjectArchiveTable table : values()) {
            assertEquals(0, target.jdbc.queryForObject("SELECT COUNT(*) FROM " + table.table, Integer.class), table.section);
        }
    }

    /** 同一渠道在来源环境改过编码时，当前方案与历史任务必须各自匹配原本的编码。 */
    @Test
    void shouldKeepCurrentAndHistoricalChannelCodesSeparate() throws Exception {
        Fixture source = createFixture();
        seedProject(source, true);
        source.jdbc.update("UPDATE geo_llm_channel SET channel_code = 'CURRENT' WHERE id = 7");
        ObjectNode archive = format.parseArchive(source.service.exportProject(10));
        assertEquals("CURRENT", archive.withArray(PLAN_CHANNEL.section).get(0).path("channel_code").asText());
        assertEquals("TEST", archive.withArray(RESULT.section).get(0).path("channel_code").asText());
        Fixture target = createFixture();
        target.jdbc.update("INSERT INTO geo_llm_channel (id,channel_code,channel_name,provider_code,strategy_bean_name) VALUES (700,'CURRENT','当前渠道','MOCK','mock')");
        target.service.importProject(archive);
        assertEquals(700, target.jdbc.queryForObject("SELECT channel_id FROM geo_diagnosis_plan_channel", Long.class));
        assertEquals(7, target.jdbc.queryForObject("SELECT channel_id FROM geo_diagnosis_task_result", Long.class));
        assertEquals(7, parseStored(target, "geo_diagnosis_task_report", "summary").path("evidence").get(0).path("channelId").asLong());
    }

    /**
     * 从生产 DDL 创建内存库，仅替换 H2 不支持的 MySQL 存储和 JSON 类型语法，保留外键及检查约束。
     * @return 带真实事务代理的隔离测试环境
     * @throws Exception DDL 加载失败
     */
    private Fixture createFixture() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String ddl;
        try (var stream = new ClassPathResource("db/mysql/001_init.sql").getInputStream()) {
            ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var statements = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\(.*?;", Pattern.DOTALL).matcher(ddl);
        Set<String> tables = new HashSet<>(Set.of("geo_llm_channel"));
        Arrays.stream(values()).forEach(table -> tables.add(table.table));
        while (statements.find()) {
            if (tables.contains(statements.group(1))) {
                jdbc.execute(statements.group().replaceAll("COMMENT\\s+'[^']*'", "")
                        .replaceAll("ENGINE=InnoDB.*?;", ";").replace("BIGINT UNSIGNED", "BIGINT")
                        .replaceAll("CHARACTER SET ascii COLLATE ascii_bin", "")
                        .replaceAll("\\bJSON\\b|\\bLONGTEXT\\b|\\bTEXT\\b", "VARCHAR(10000000)")
                        .replaceAll("ON UPDATE CURRENT_TIMESTAMP\\(3\\)", "")
                        .replaceAll("(?m)^\\s*KEY [^\\n]+\\n", "")
                        .replaceAll("UNIQUE KEY (\\w+)", "CONSTRAINT $1 UNIQUE")
                        .replaceAll(",\\s*\\)\\s*;", ") ;"));
            }
        }
        jdbc.update("INSERT INTO geo_llm_channel (id,channel_code,channel_name,provider_code,strategy_bean_name,base_url,api_key,model_name,score,enabled) VALUES (7,'TEST','历史渠道','MOCK','mock','https://example.test','source-secret','model-a',1,TRUE)");
        var repository = new ProjectArchiveRepository();
        ReflectionTestUtils.setField(repository, "jdbcTemplate", jdbc);
        var references = new ProjectArchiveReferences();
        ReflectionTestUtils.setField(references, "format", format);
        var service = new ProjectArchiveService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "format", format);
        ReflectionTestUtils.setField(service, "references", references);
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return new Fixture(jdbc, repository, (ProjectArchiveService) proxy.getProxy());
    }

    /**
     * 建立一个有报告证据和原始响应的最小完整项目，标识故意与目标自增值不同。
     * @param fixture 测试库
     * @param withDiagnosis 是否建立诊断链路
     */
    private void seedProject(Fixture fixture, boolean withDiagnosis) {
        fixture.jdbc.update("INSERT INTO geo_project_info (id,name,website_url,industry,core_features,target_audience,aliases,competitors,keywords) VALUES (10,'迁移项目','https://example.test','软件','功能','用户','[]','[]','[]')");
        if (!withDiagnosis) {
            return;
        }
        fixture.jdbc.update("INSERT INTO geo_diagnosis_plan (id,name,project_id,repeat_count) VALUES (20,'诊断方案',10,1)");
        fixture.jdbc.update("INSERT INTO geo_diagnosis_plan_question (id,plan_id,question_text,source,intent,brand_scope,language,sort_order) VALUES (30,20,'测试问题','MANUAL','AWARENESS','BRAND','zh',0)");
        fixture.jdbc.update("INSERT INTO geo_diagnosis_plan_channel (id,plan_id,channel_id) VALUES (40,20,7)");
        ObjectNode question = JsonNodeFactory.instance.objectNode().put("id", 30).put("sortOrder", 0).put("text", "测试问题")
                .put("source", "MANUAL").put("intent", "AWARENESS").put("brandScope", "BRAND").put("language", "zh");
        ObjectNode channel = JsonNodeFactory.instance.objectNode().put("id", 7).put("channelCode", "TEST")
                .put("channelName", "历史渠道").put("modelName", "model-a").put("baseUrl", "https://example.test")
                .put("strategyBeanName", "mock").put("baseUrlDigest", "source-digest").put("apiKey", "must-not-leak");
        ObjectNode plan = JsonNodeFactory.instance.objectNode().put("version", 1).put("planId", 20).put("planName", "诊断方案");
        plan.putObject("project").put("id", 10).put("name", "迁移项目");
        plan.putArray("questions").add(question);
        plan.putArray("channels").add(channel);
        fixture.jdbc.update("INSERT INTO geo_diagnosis_task (id,request_id,plan_id,project_id,plan_snapshot,status) VALUES (50,'source-request',20,10,?,'FAILED')", plan.toString());
        fixture.jdbc.update("INSERT INTO geo_diagnosis_task_result (id,task_id,question_id,question_sort_order,question_snapshot,channel_id,channel_snapshot,repeat_index,status,answer_text,citations,usage_info,raw_response) VALUES (60,50,30,0,?,7,?,1,'SUCCEEDED','回答正文','[]','{\"total_tokens\":10}','{\"id\":\"vendor-non-numeric\"}')", question.toString(), channel.toString());
        fixture.jdbc.update("INSERT INTO geo_diagnosis_task_report (id,task_id,status,judge_channel_snapshot,summary) VALUES (70,50,'SUCCEEDED',?,?)", channel.toString(), "{\"evidence\":[{\"resultId\":60,\"channelId\":7}],\"issues\":[{\"id\":\"P1\",\"channelId\":null}]}");
        fixture.jdbc.update("INSERT INTO geo_diagnosis_task_result_analysis (id,report_id,task_result_id,status,brand_sentiment,brands,points,sources) VALUES (80,70,60,'SUCCEEDED','NEUTRAL','[]','[]','[]')");
    }

    /**
     * 读取测试库的 JSON 列，只接收测试中固定表列名。
     * @param fixture 测试环境
     * @param table 表名
     * @param column JSON 字段
     * @return JSON 值
     */
    private JsonNode parseStored(Fixture fixture, String table, String column) {
        return format.parseArchive(fixture.jdbc.queryForObject("SELECT " + column + " FROM " + table, String.class).getBytes(StandardCharsets.UTF_8));
    }

    /** 单个内存数据库及其仓储和事务代理，不连接应用实际数据库。 */
    private record Fixture(JdbcTemplate jdbc, ProjectArchiveRepository repository, ProjectArchiveService service) { }
}
