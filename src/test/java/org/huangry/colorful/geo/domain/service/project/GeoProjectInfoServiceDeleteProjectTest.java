package org.huangry.colorful.geo.domain.service.project;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisPlanChannelDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisPlanDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisPlanQuestionDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskReportDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskResultAnalysisDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskResultDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoProjectInfoDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 用生产 DDL 转换出的隔离 H2 表和真实 Mapper 验证项目删除范围；不代表 MySQL 实例联调或并发锁行为。 */
class GeoProjectInfoServiceDeleteProjectTest {
    /** 项目删除涉及的八张表及外键指向的渠道表。 */
    private static final List<String> TABLES = List.of("geo_llm_channel", "geo_project_info", "geo_diagnosis_plan",
            "geo_diagnosis_plan_question", "geo_diagnosis_plan_channel", "geo_diagnosis_task", "geo_diagnosis_task_result",
            "geo_diagnosis_task_report", "geo_diagnosis_task_result_analysis");
    private JdbcTemplate jdbc;
    private GeoProjectInfoService service;

    /**
     * 每个用例使用独立内存库，按生产 DDL 建表并注入真实 Mapper。
     * @throws Exception 建表或 Mapper 初始化失败
     */
    @BeforeEach
    void createDatabase() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createTables();
        var configuration = new MybatisConfiguration();
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        service = new GeoProjectInfoService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper(session, GeoProjectInfoDao.class));
        ReflectionTestUtils.setField(service, "planDao", mapper(session, GeoDiagnosisPlanDao.class));
        ReflectionTestUtils.setField(service, "questionDao", mapper(session, GeoDiagnosisPlanQuestionDao.class));
        ReflectionTestUtils.setField(service, "planChannelDao", mapper(session, GeoDiagnosisPlanChannelDao.class));
        ReflectionTestUtils.setField(service, "taskDao", mapper(session, GeoDiagnosisTaskDao.class));
        ReflectionTestUtils.setField(service, "taskResultDao", mapper(session, GeoDiagnosisTaskResultDao.class));
        ReflectionTestUtils.setField(service, "taskReportDao", mapper(session, GeoDiagnosisTaskReportDao.class));
        ReflectionTestUtils.setField(service, "taskResultAnalysisDao", mapper(session, GeoDiagnosisTaskResultAnalysisDao.class));
        jdbc.update("INSERT INTO geo_llm_channel (id,channel_code,channel_name,provider_code,strategy_bean_name,base_url,api_key,model_name,score,enabled) VALUES (7,'TEST','渠道','MOCK','mock','https://example.test','secret','model-a',1,TRUE)");
    }

    /** 执行中的任务和报告也一并删除，八张表只清除目标项目的数据，渠道及其他项目保留。 */
    @Test
    void shouldDeleteProjectWithAllDiagnosisDataIncludingRunningTasks() {
        seedProject(10, 100, "RUNNING", "RUNNING");
        seedProject(11, 200, "SUCCEEDED", "SUCCEEDED");

        service.deleteProject(10);

        for (String table : TABLES.subList(1, TABLES.size())) {
            assertEquals(1, count(table), table);
        }
        assertEquals(11, jdbc.queryForObject("SELECT id FROM geo_project_info", Long.class));
        assertEquals(200, jdbc.queryForObject("SELECT id FROM geo_diagnosis_task", Long.class));
        assertEquals(1, count("geo_llm_channel"));
    }

    /** 方案改挂其他项目后，执行时属于本项目的历史任务随本项目删除，改挂后的方案保留。 */
    @Test
    void shouldDeleteTaskExecutedUnderProjectWhosePlanMovedToAnotherProject() {
        seedProject(10, 100, "SUCCEEDED", "SUCCEEDED");
        seedProject(11, 200, "SUCCEEDED", "SUCCEEDED");
        jdbc.update("UPDATE geo_diagnosis_plan SET project_id = 11 WHERE id = 100");

        service.deleteProject(10);

        assertEquals(0, count("geo_project_info WHERE id = 10"));
        assertEquals(2, count("geo_diagnosis_plan"));
        assertEquals(List.of(200L), jdbc.queryForList("SELECT id FROM geo_diagnosis_task", Long.class));
        assertEquals(1, count("geo_diagnosis_task_result_analysis"));
    }

    /** 没有方案和任务的项目只删除基础档案。 */
    @Test
    void shouldDeleteProjectWithoutDiagnosisData() {
        insertProject(10);

        service.deleteProject(10);

        assertEquals(0, count("geo_project_info"));
    }

    /** 不存在的项目返回明确的 404 语义，不删除任何数据。 */
    @Test
    void shouldRejectMissingProject() {
        seedProject(11, 200, "SUCCEEDED", "SUCCEEDED");

        assertThrows(NoSuchElementException.class, () -> service.deleteProject(10));
        assertEquals(1, count("geo_project_info"));
        assertEquals(1, count("geo_diagnosis_task_result_analysis"));
    }

    /**
     * 写入一个带完整诊断链路的项目：方案、问题、渠道关联、任务、诊断结果、报告和回答分析各一条。
     * @param projectId 项目 ID
     * @param baseId 其余各表 ID 的起点，避免两个项目主键冲突
     * @param taskStatus 任务状态
     * @param reportStatus 报告状态
     */
    private void seedProject(long projectId, long baseId, String taskStatus, String reportStatus) {
        insertProject(projectId);
        jdbc.update("INSERT INTO geo_diagnosis_plan (id,name,project_id,repeat_count) VALUES (?,'方案',?,1)", baseId, projectId);
        jdbc.update("INSERT INTO geo_diagnosis_plan_question (id,plan_id,question_text,source,intent,brand_scope,language,sort_order) VALUES (?,?,'问题','MANUAL','AWARENESS','BRAND','zh',0)", baseId + 1, baseId);
        jdbc.update("INSERT INTO geo_diagnosis_plan_channel (id,plan_id,channel_id) VALUES (?,?,7)", baseId + 2, baseId);
        jdbc.update("INSERT INTO geo_diagnosis_task (id,plan_id,project_id,plan_snapshot,status) VALUES (?,?,?,'{}',?)", baseId, baseId, projectId, taskStatus);
        jdbc.update("INSERT INTO geo_diagnosis_task_result (id,task_id,question_id,question_sort_order,question_snapshot,channel_id,channel_snapshot,repeat_index,status) VALUES (?,?,?,0,'{}',7,'{}',1,'RUNNING')", baseId + 3, baseId, baseId + 1);
        jdbc.update("INSERT INTO geo_diagnosis_task_report (id,task_id,status) VALUES (?,?,?)", baseId + 4, baseId, reportStatus);
        jdbc.update("INSERT INTO geo_diagnosis_task_result_analysis (id,report_id,task_result_id,status) VALUES (?,?,?,'PENDING')", baseId + 5, baseId + 4, baseId + 3);
    }

    /**
     * 写入一份最小项目基础档案。
     * @param projectId 项目 ID
     */
    private void insertProject(long projectId) {
        jdbc.update("INSERT INTO geo_project_info (id,name,website_url,industry,core_features,target_audience,aliases,competitors,keywords) VALUES (?,'项目','https://example.test','软件','功能','用户','[]','[]','[]')", projectId);
    }

    /**
     * 统计表内行数。
     * @param tableWithCondition 测试内固定的表名及可选筛选条件
     * @return 行数
     */
    private int count(String tableWithCondition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + tableWithCondition, Integer.class);
    }

    /**
     * 注册并取得真实 Mapper 代理。
     * @param session MyBatis 会话模板
     * @param type Mapper 接口
     * @return Mapper 代理
     * @param <T> Mapper 类型
     */
    private <T> T mapper(SqlSessionTemplate session, Class<T> type) {
        if (!session.getConfiguration().hasMapper(type)) {
            session.getConfiguration().addMapper(type);
        }
        return session.getMapper(type);
    }

    /**
     * 将生产初始化 DDL 中涉及的表转换为 H2 可执行语句；保留主键、唯一约束及外键，验证删除顺序。
     * @throws Exception 读取 DDL 失败
     */
    private void createTables() throws Exception {
        String ddl;
        try (var stream = new ClassPathResource("db/mysql/001_init.sql").getInputStream()) {
            ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var statements = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\(.*?;", Pattern.DOTALL).matcher(ddl);
        Set<String> tables = Set.copyOf(TABLES);
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
    }
}
