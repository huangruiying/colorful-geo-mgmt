package org.huangry.colorful.geo.domain.service.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.repository.*;
import org.huangry.colorful.geo.infrastructure.repository.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.text.Normalizer;

/**
 * 整体保存方案及其问题、渠道；三张表在同一事务内更新，避免半个方案。
 */
@Service
public class GeoDiagnosisPlanService extends ServiceImpl<GeoDiagnosisPlanDao, GeoDiagnosisPlanEntity> {
	@Resource
	private GeoDiagnosisPlanQuestionDao questionDao;
	@Resource
	private GeoDiagnosisPlanChannelDao channelDao;
	@Resource
	private GeoProjectInfoDao projectDao;
	@Resource
	private GeoLlmChannelDao llmChannelDao;

	/**
	 * 修改时锁定方案后替换子集合；子项失败使方案及旧集合一并回滚。
	 */
	@Transactional
	public GeoDiagnosisPlanEntity savePlan(GeoDiagnosisPlanEntity plan,
	                                       List<GeoDiagnosisPlanQuestionEntity> questions, List<GeoDiagnosisPlanChannelEntity> channels) {
		// 1. 校验关联及问题，拒绝失效渠道和会抬高自然发现率的品牌问题。
		validatePlan(plan, questions, channels);
		// 2. 锁定已有方案后保存公共配置，修改不覆盖创建时间。
		if (plan.getId() == null) {
			if (! save(plan)) throw new IllegalStateException("诊断方案创建失败");
		} else {
			GeoDiagnosisPlanEntity existing = getOne(new LambdaQueryWrapper<GeoDiagnosisPlanEntity>()
					.eq(GeoDiagnosisPlanEntity::getId, plan.getId()).last("FOR UPDATE"));
			if (existing == null) throw new NoSuchElementException("诊断方案不存在");
			plan.setUpdatedAt(LocalDateTime.now());
			if (! updateById(plan)) throw new IllegalStateException("诊断方案修改失败");
			questionDao.delete(new LambdaQueryWrapper<GeoDiagnosisPlanQuestionEntity>()
					.eq(GeoDiagnosisPlanQuestionEntity::getPlanId, plan.getId()));
			channelDao.delete(new LambdaQueryWrapper<GeoDiagnosisPlanChannelEntity>()
					.eq(GeoDiagnosisPlanChannelEntity::getPlanId, plan.getId()));
		}
		// 3. 在同一事务中写入完整的问题和渠道集合。
		for (GeoDiagnosisPlanQuestionEntity question : questions) {
			question.setPlanId(plan.getId());
			questionDao.insert(question);
		}
		for (GeoDiagnosisPlanChannelEntity channel : channels) {
			channel.setPlanId(plan.getId());
			channelDao.insert(channel);
		}
		return getById(plan.getId());
	}

	/**
	 * 保存前确认基础档案存在、渠道均启用，避免创建不可执行的方案。
	 */
	private void validatePlan(GeoDiagnosisPlanEntity plan, List<GeoDiagnosisPlanQuestionEntity> questions,
	                          List<GeoDiagnosisPlanChannelEntity> channels) {
		// 公共配置范围与数据库容量保持一致。
		if (plan.getName() == null || plan.getName().isBlank() || plan.getName().strip().length() > 200)
			throw new IllegalArgumentException("方案名称必填，最多 200 字符");
		plan.setName(plan.getName().strip());
		if (plan.getRepeatCount() == null || plan.getRepeatCount() < 1 || plan.getRepeatCount() > 10)
			throw new IllegalArgumentException("每题重复执行次数必须为 1 到 10");
		GeoProjectInfoEntity project = plan.getProjectId() == null ? null : projectDao.selectById(plan.getProjectId());
		if (project == null) throw new IllegalArgumentException("请选择有效项目");
		validateQuestions(project, questions);
		// 只接受显式选择的渠道，不使用内部生成问题的默认渠道替代。
		if (channels == null || channels.isEmpty() || channels.size() > 20)
			throw new IllegalArgumentException("请选择 1 到 20 个测试渠道");
		Set<Long> ids = new HashSet<>();
		for (GeoDiagnosisPlanChannelEntity channel : channels) {
			if (channel == null || channel.getChannelId() == null || ! ids.add(channel.getChannelId()))
				throw new IllegalArgumentException("测试渠道不能为空或重复");
			GeoLlmChannelEntity model = llmChannelDao.selectById(channel.getChannelId());
			if (model == null || ! Boolean.TRUE.equals(model.getEnabled()))
				throw new IllegalArgumentException("测试渠道不存在或已停用，请重新选择");
		}
	}

	/**
	 * 生成与保存共用题目校验；非品牌题不能包含本项目名称或别名。
	 */
	public void validateQuestions(GeoProjectInfoEntity project, List<GeoDiagnosisPlanQuestionEntity> questions) {
		// 1. 拒绝空集合和重复文本，不把非法候选静默过滤成成功。
		if (questions == null || questions.isEmpty() || questions.size() > 200)
			throw new IllegalArgumentException("问题集合必须包含 1 到 200 个问题");
		Set<String> texts = new HashSet<>();
		List<String> brands = new ArrayList<>();
		brands.add(project.getName());
		if (project.getAliases() != null) brands.addAll(project.getAliases());
		// 2. 独立校验来源、意图、品牌范围和语言，顺序以输入数组为准。
		for (int index = 0; index < questions.size(); index++) {
			GeoDiagnosisPlanQuestionEntity question = questions.get(index);
			if (question == null || question.getQuestionText() == null || question.getQuestionText().isBlank()
					|| question.getQuestionText().length() > 2000)
				throw new IllegalArgumentException("问题内容必填，最多 2000 字符");
			question.setQuestionText(question.getQuestionText().strip());
			String normalized = normalizeText(question.getQuestionText());
			if (! texts.add(normalized)) throw new IllegalArgumentException("问题内容重复，请删除重复问题");
			if (! Set.of("MANUAL", "GENERATED").contains(Objects.toString(question.getSource(), "")))
				throw new IllegalArgumentException("问题来源无效");
			if (! Set.of("RECOMMENDATION", "PROBLEM_SOLVING", "COMPARISON", "PURCHASE", "TECHNICAL", "AWARENESS")
					.contains(Objects.toString(question.getIntent(), "")))
				throw new IllegalArgumentException("问题意图无效");
			if (! Set.of("NON_BRAND", "BRAND", "COMPARISON").contains(Objects.toString(question.getBrandScope(), "")))
				throw new IllegalArgumentException("品牌范围无效");
			if (question.getLanguage() == null || question.getLanguage().isBlank() || question.getLanguage().length() > 100)
				throw new IllegalArgumentException("问题语言必填，最多 100 字符");
			question.setLanguage(question.getLanguage().strip());
			question.setSortOrder(index);
			// 品牌比较同样不能计入自然发现，非品牌标记必须与文本一致。
			if ("NON_BRAND".equals(question.getBrandScope()) && brands.stream()
					.filter(brand -> brand != null && ! brand.isBlank())
					.anyMatch(brand -> normalized.contains(normalizeText(brand))))
				throw new IllegalArgumentException("非品牌问题包含项目名称或别名，请调整内容或品牌范围");
		}
	}

	/**
	 * 忽略大小写、全半角与空白差异，阻止同一问题重复计入诊断执行量。
	 */
	private String normalizeText(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
	}

	/**
	 * 固定按方案顺序回读问题。
	 */
	public List<GeoDiagnosisPlanQuestionEntity> listQuestions(long planId) {
		return questionDao.selectList(new LambdaQueryWrapper<GeoDiagnosisPlanQuestionEntity>()
				.eq(GeoDiagnosisPlanQuestionEntity::getPlanId, planId)
				.orderByAsc(GeoDiagnosisPlanQuestionEntity::getSortOrder));
	}

	/**
	 * 渠道顺序只影响展示，不改变调用优先级。
	 */
	public List<GeoDiagnosisPlanChannelEntity> listChannels(long planId) {
		return channelDao.selectList(new LambdaQueryWrapper<GeoDiagnosisPlanChannelEntity>()
				.eq(GeoDiagnosisPlanChannelEntity::getPlanId, planId)
				.orderByAsc(GeoDiagnosisPlanChannelEntity::getId));
	}
}
