package org.huangry.colorful.geo.application.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.model.*;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisPlanService;
import org.huangry.colorful.geo.domain.service.QuestionGenerateService;
import org.huangry.colorful.geo.domain.service.project.GeoProjectInfoService;
import org.huangry.colorful.geo.infrastructure.repository.entity.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** 诊断方案接口编排；转换方案模型并生成候选，不启动诊断任务。 */
@Service
public class DiagnosisPlanBusiness {
    @Resource
    private GeoDiagnosisPlanService planService;
    @Resource
    private GeoProjectInfoService projectService;
    @Resource
    private QuestionGenerateService questionGenerateService;

    /** 显式映射页面已确认的集合；校验与事务落库交由领域服务。 */
    public DiagnosisPlanResponse save(Long id, SaveDiagnosisPlanRequest request) {
        // 1. 请求只提供可维护字段，ID 由路径或数据库决定。
        GeoDiagnosisPlanEntity plan = new GeoDiagnosisPlanEntity();
        plan.setId(id);
        plan.setName(request.name());
        plan.setProjectId(request.projectId());
        plan.setRepeatCount(request.repeatCount());
        if (request.questions() == null || request.channelIds() == null)
            throw new IllegalArgumentException("问题集合与测试渠道必填");
        // 2. 集合项逐字段转换，不接受数据库主键或其他方案关联。
        List<GeoDiagnosisPlanQuestionEntity> questions = request.questions().stream().map(this::toEntity).toList();
        List<GeoDiagnosisPlanChannelEntity> channels = request.channelIds().stream().map(channelId -> {
            var entity = new GeoDiagnosisPlanChannelEntity();
            entity.setChannelId(channelId);
            return entity;
        }).toList();
        // 3. 返回数据库实际保存后的方案，包含生成标识和更新时间。
        return toResponse(planService.savePlan(plan, questions, channels));
    }

    /** 缺失方案返回明确错误，详情包含整个问题集合。 */
    public DiagnosisPlanResponse detail(long id) {
        var plan = planService.getById(id);
        if (plan == null) throw new NoSuchElementException("诊断方案不存在");
        return toResponse(plan);
    }

    /** 列表按方案名筛选并分页；摘要不加载全部问题正文。 */
    public PlanPage list(String keyword, int page, int size) {
        // 校验分页上限，用户关键词通过绑定参数查询。
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("每页条数为 1 到 100，页码从零开始");
        if (keyword != null && keyword.length() > 200) throw new IllegalArgumentException("关键词最多 200 字符");
        var query = new LambdaQueryWrapper<GeoDiagnosisPlanEntity>()
                .like(keyword != null && !keyword.isBlank(), GeoDiagnosisPlanEntity::getName, keyword);
        long total = planService.count(query);
        var plans = planService.list(query.orderByDesc(GeoDiagnosisPlanEntity::getId)
                .last("LIMIT " + size + " OFFSET " + ((long) page * size)));
        // 项目名称批量读取；列表无需逐条读取问题和渠道。
        Map<Long, String> names = new HashMap<>();
        if (!plans.isEmpty()) {
            projectService.listByIds(plans.stream().map(GeoDiagnosisPlanEntity::getProjectId).distinct().toList())
                    .forEach(project -> names.put(project.getId(), project.getName()));
        }
        var items = plans.stream().map(plan -> new PlanSummary(plan.getId(), plan.getName(),
                plan.getProjectId(), names.get(plan.getProjectId()), plan.getRepeatCount(), plan.getUpdatedAt())).toList();
        return new PlanPage(items, total, page, size);
    }

    /** 候选仅返回给页面，不修改已保存方案或用户现有集合。 */
    public List<DiagnosisQuestion> generate(GenerateDiagnosisQuestionsRequest request) {
        var project = request.projectId() == null ? null : projectService.getById(request.projectId());
        if (project == null) throw new IllegalArgumentException("请选择有效项目");
        return questionGenerateService.generate(project, request.channelCode(), request.count(), request.language())
                .stream().map(this::toQuestion).toList();
    }

    /** 构造详情，渠道关联只返回 ID，凭据留在模型配置表。 */
    private DiagnosisPlanResponse toResponse(GeoDiagnosisPlanEntity plan) {
        var project = projectService.getById(plan.getProjectId());
        return new DiagnosisPlanResponse(plan.getId(), plan.getName(), plan.getProjectId(),
                project == null ? null : project.getName(), plan.getRepeatCount(),
                planService.listChannels(plan.getId()).stream().map(GeoDiagnosisPlanChannelEntity::getChannelId).toList(),
                planService.listQuestions(plan.getId()).stream().map(this::toQuestion).toList(), plan.getUpdatedAt());
    }

    /** 输入枚举仅转换为受控数据库值，空项留给规则校验。 */
    private GeoDiagnosisPlanQuestionEntity toEntity(DiagnosisQuestion question) {
        if (question == null) throw new IllegalArgumentException("问题不能为空");
        var entity = new GeoDiagnosisPlanQuestionEntity();
        entity.setQuestionText(question.text());
        entity.setSource(question.source() == null ? null : question.source().name());
        entity.setIntent(question.intent() == null ? null : question.intent().name());
        entity.setBrandScope(question.brandScope() == null ? null : question.brandScope().name());
        entity.setLanguage(question.language());
        return entity;
    }

    /** 已校验的数据库值显式映射到接口枚举。 */
    private DiagnosisQuestion toQuestion(GeoDiagnosisPlanQuestionEntity entity) {
        return new DiagnosisQuestion(entity.getQuestionText(), DiagnosisQuestion.Source.valueOf(entity.getSource()),
                DiagnosisQuestion.Intent.valueOf(entity.getIntent()), DiagnosisQuestion.BrandScope.valueOf(entity.getBrandScope()),
                entity.getLanguage());
    }

    /** 列表摘要不携带问题正文或任何模型密钥。 */
    public record PlanSummary(Long id, String name, Long projectId, String projectName,
                              int repeatCount, java.time.LocalDateTime updatedAt) {}
    /** 页码从零开始，与项目和模型管理保持一致。 */
    public record PlanPage(List<PlanSummary> items, long total, int page, int size) {}
}
