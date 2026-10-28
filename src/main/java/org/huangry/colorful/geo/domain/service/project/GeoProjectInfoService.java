package org.huangry.colorful.geo.domain.service.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.common.model.PageQuery;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisPlanChannelDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisPlanDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisPlanQuestionDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskReportDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskResultAnalysisDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskResultDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoProjectInfoDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanChannelEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanQuestionEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskReportEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoProjectInfoEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;

/** 项目基础档案服务；校验、保存、查询及整体删除项目，不执行分析任务。 */
@Service
@Slf4j
public class GeoProjectInfoService extends ServiceImpl<GeoProjectInfoDao, GeoProjectInfoEntity> {
    @Resource
    private GeoDiagnosisPlanDao planDao;
    @Resource
    private GeoDiagnosisPlanQuestionDao questionDao;
    @Resource
    private GeoDiagnosisPlanChannelDao planChannelDao;
    @Resource
    private GeoDiagnosisTaskDao taskDao;
    @Resource
    private GeoDiagnosisTaskResultDao taskResultDao;
    @Resource
    private GeoDiagnosisTaskReportDao taskReportDao;
    @Resource
    private GeoDiagnosisTaskResultAnalysisDao taskResultAnalysisDao;

    /** 校验并持久化项目；返回数据库生成的 ID 与时间，失败则回滚。 */
    @Transactional
    public GeoProjectInfoEntity createProject(GeoProjectInfoEntity project) {
        // 1. 校验必填资料及长度，确保档案可作为后续任务的基础输入。
        validateProject(project);
        // 2. 新建记录的标识与时间由数据库生成，JSON 列由类型处理器负责。
        project.setId(null);
        project.setCreatedAt(null);
        project.setUpdatedAt(null);
        if (!save(project)) throw new IllegalStateException("项目创建失败");
        // 3. 回读数据库生成的标识及时间。
        return getById(project.getId());
    }

    /**
     * 项目列表查询，可按名称筛选，最新创建的在前。
     *
     * @param pageQuery 已校验的分页参数及项目名称关键词
     * @return 当前页项目
     */
    public List<GeoProjectInfoEntity> listProjects(PageQuery pageQuery) {
        return list(buildProjectQuery(pageQuery.keyword()).orderByDesc(GeoProjectInfoEntity::getId)
                .last(pageQuery.limitClause()));
    }

    /** 使用与列表相同的筛选条件统计项目数量。 */
    public long countProjects(String keyword) {
        return count(buildProjectQuery(keyword));
    }

    /** 绑定名称查询参数；用户输入不拼入 SQL。 */
    private LambdaQueryWrapper<GeoProjectInfoEntity> buildProjectQuery(String keyword) {
        return new LambdaQueryWrapper<GeoProjectInfoEntity>().like(keyword != null && !keyword.isBlank(),
                GeoProjectInfoEntity::getName, keyword);
    }

    /**
     * 删除项目及其全部诊断数据，不论任务和报告是否仍在执行。
     * <p>写入：删除回答分析、诊断报告、诊断结果、诊断任务、方案问题、方案渠道、诊断方案及项目本身八张表的相关行；
     * 任务按项目 ID 或所属方案筛选，与项目导出口径一致。同一事务执行，任一步失败全部回滚。</p>
     * <p>不删除模型渠道、内容优化与发布记录；后台正在执行的任务或报告写回时因记录不存在而失败，由执行器记录中断日志。</p>
     *
     * @param projectId 项目 ID
     * @throws NoSuchElementException 项目不存在
     */
    @Transactional
    public void deleteProject(long projectId) {
        // 1. 依次锁定项目、方案和任务，删除期间新建方案、任务或报告会等待并因外键失效被拒绝
        lockProject(projectId);
        List<Long> planIds = lockPlanIds(projectId);
        List<Long> taskIds = lockTaskIds(projectId, planIds);

        // 2. 先删任务及其下的结果、报告、回答分析，再删方案及其子表，最后删项目，满足外键依赖
        deleteTasks(taskIds);
        deletePlans(planIds);
        removeById(projectId);
        log.info("删除项目完成 projectId={}, planCount={}, taskCount={}", projectId, planIds.size(), taskIds.size());
    }

    /**
     * 锁定待删除项目，阻止并发新建方案挂到该项目。
     *
     * @param projectId 项目 ID
     * @throws NoSuchElementException 项目不存在
     */
    private void lockProject(long projectId) {
        GeoProjectInfoEntity project = getOne(new LambdaQueryWrapper<GeoProjectInfoEntity>()
                .select(GeoProjectInfoEntity::getId).eq(GeoProjectInfoEntity::getId, projectId).last("FOR UPDATE"));
        if (project == null) {
            throw new NoSuchElementException("项目不存在");
        }
    }

    /**
     * 锁定项目下的诊断方案，阻止并发新建任务挂到这些方案。
     *
     * @param projectId 项目 ID
     * @return 项目下的方案 ID，没有方案时为空列表
     */
    private List<Long> lockPlanIds(long projectId) {
        return planDao.selectList(new LambdaQueryWrapper<GeoDiagnosisPlanEntity>()
                        .select(GeoDiagnosisPlanEntity::getId).eq(GeoDiagnosisPlanEntity::getProjectId, projectId)
                        .last("FOR UPDATE"))
                .stream().map(GeoDiagnosisPlanEntity::getId).toList();
    }

    /**
     * 锁定项目的诊断任务，阻止并发生成报告或重新发起任务。
     *
     * @param projectId 项目 ID
     * @param planIds 项目下的方案 ID
     * @return 执行时属于该项目或来自其方案的任务 ID，没有任务时为空列表
     */
    private List<Long> lockTaskIds(long projectId, List<Long> planIds) {
        // 方案可改挂其他项目，任务按执行时项目和当前所属方案两条路径归属，与导出范围一致
        return taskDao.selectList(new LambdaQueryWrapper<GeoDiagnosisTaskEntity>()
                        .select(GeoDiagnosisTaskEntity::getId)
                        .eq(GeoDiagnosisTaskEntity::getProjectId, projectId)
                        .or(!planIds.isEmpty(), query -> query.in(GeoDiagnosisTaskEntity::getPlanId, planIds))
                        .last("FOR UPDATE"))
                .stream().map(GeoDiagnosisTaskEntity::getId).toList();
    }

    /**
     * 删除任务及其下的回答分析、诊断报告和诊断结果。
     *
     * @param taskIds 待删除的任务 ID
     */
    private void deleteTasks(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        // 回答分析挂在报告下，按报告删除；报告与被分析的结果属于同一任务，删完分析后结果可直接删除
        List<Long> reportIds = taskReportDao.selectList(new LambdaQueryWrapper<GeoDiagnosisTaskReportEntity>()
                        .select(GeoDiagnosisTaskReportEntity::getId).in(GeoDiagnosisTaskReportEntity::getTaskId, taskIds))
                .stream().map(GeoDiagnosisTaskReportEntity::getId).toList();
        if (!reportIds.isEmpty()) {
            taskResultAnalysisDao.delete(new LambdaQueryWrapper<GeoDiagnosisTaskResultAnalysisEntity>()
                    .in(GeoDiagnosisTaskResultAnalysisEntity::getReportId, reportIds));
            taskReportDao.deleteByIds(reportIds);
        }
        taskResultDao.delete(new LambdaQueryWrapper<GeoDiagnosisTaskResultEntity>()
                .in(GeoDiagnosisTaskResultEntity::getTaskId, taskIds));
        taskDao.deleteByIds(taskIds);
    }

    /**
     * 删除诊断方案及其问题、渠道关联；渠道配置本身保留。
     *
     * @param planIds 待删除的方案 ID
     */
    private void deletePlans(List<Long> planIds) {
        if (planIds.isEmpty()) {
            return;
        }
        questionDao.delete(new LambdaQueryWrapper<GeoDiagnosisPlanQuestionEntity>()
                .in(GeoDiagnosisPlanQuestionEntity::getPlanId, planIds));
        planChannelDao.delete(new LambdaQueryWrapper<GeoDiagnosisPlanChannelEntity>()
                .in(GeoDiagnosisPlanChannelEntity::getPlanId, planIds));
        planDao.deleteByIds(planIds);
    }

    /**
     * 校验并规范化项目必填资料、URL 与可选配置，防止无效资料或超长字段落库。
     * @param project 待保存项目资料
     * @throws IllegalArgumentException 必填项、长度、列表或地址不符合要求
     */
    private void validateProject(GeoProjectInfoEntity project) {
        // 1. 必填项为空或超过字段容量时，拒绝创建不完整档案。
        project.setName(validateText(project.getName(), "项目名称", 200, true));
        project.setWebsiteUrl(validateText(project.getWebsiteUrl(), "官网或资料 URL", 2048, true));
        project.setIndustry(validateText(project.getIndustry(), "行业或品类", 200, true));
        project.setCoreFeatures(validateText(project.getCoreFeatures(), "核心功能", 5000, true));
        project.setTargetAudience(validateText(project.getTargetAudience(), "目标用户群体", 5000, true));
        // 2. URL 只保存 HTTP(S) 资料地址，不在创建时抓取远程页面。
        validateProjectWebsiteUrl(project.getWebsiteUrl());
        // 3. 可选字段保留空值，列表统一为空数组并限制单项长度。
        project.setTargetMarket(validateText(project.getTargetMarket(), "目标市场", 200, false));
        project.setTargetLanguage(validateText(project.getTargetLanguage(), "目标语言", 100, false));
        project.setSellingPoints(validateText(project.getSellingPoints(), "核心卖点", 5000, false));
        project.setPricing(validateText(project.getPricing(), "价格说明", 2000, false));
        project.setAliases(validateEntries(project.getAliases(), "别名或品牌名"));
        project.setCompetitors(validateEntries(project.getCompetitors(), "主要竞品"));
        project.setKeywords(validateEntries(project.getKeywords(), "目标关键词"));
    }

    /** 返回去除首尾空白的字段；必填空值与超长内容返回明确输入错误。 */
    private String validateText(String value, String label, int maxLength, boolean required) {
        String text = value == null ? null : value.strip();
        if (required && (text == null || text.isEmpty())) throw new IllegalArgumentException("请填写" + label);
        if (text != null && text.length() > maxLength) throw new IllegalArgumentException(label + "最多 " + maxLength + " 字符");
        return text;
    }

    /** 校验可选列表，每项最多 200 字符，最多 50 项，去除重复值。 */
    private List<String> validateEntries(List<String> entries, String label) {
        if (entries == null) return List.of();
        if (entries.size() > 50) throw new IllegalArgumentException(label + "最多 50 项");
        return entries.stream().map(value -> validateText(value, label, 200, true)).distinct().toList();
    }

    /**
     * 项目创建与迁移共用的资料地址校验，不访问远程页面。
     * @param value 官网或资料地址
     * @throws IllegalArgumentException 地址不是有效的 HTTP(S) 地址
     */
    public void validateProjectWebsiteUrl(String value) {
        try {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException();
            }
            URI uri = URI.create(value);
            // 两个入口采用同一协议和主机边界，不允许文件绕过页面输入约束
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("官网或资料 URL 必须是有效的 HTTP(S) 地址");
        }
    }
}
