package org.huangry.colorful.geo.domain.service.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.huangry.colorful.geo.infrastructure.common.model.PageQuery;
import org.huangry.colorful.geo.infrastructure.repository.GeoProjectInfoDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoProjectInfoEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.List;

/** 项目基础档案服务；校验、保存及查询实体，不执行分析任务。 */
@Service
public class GeoProjectInfoService extends ServiceImpl<GeoProjectInfoDao, GeoProjectInfoEntity> {
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

    /** 校验基础必填资料、URL 与可选配置，防止无效资料或超长字段落库。 */
    private void validateProject(GeoProjectInfoEntity project) {
        // 1. 必填项为空或超过字段容量时，拒绝创建不完整档案。
        project.setName(validateText(project.getName(), "项目名称", 200, true));
        project.setWebsiteUrl(validateText(project.getWebsiteUrl(), "官网或资料 URL", 2048, true));
        project.setIndustry(validateText(project.getIndustry(), "行业或品类", 200, true));
        project.setCoreFeatures(validateText(project.getCoreFeatures(), "核心功能", 5000, true));
        project.setTargetAudience(validateText(project.getTargetAudience(), "目标用户群体", 5000, true));
        // 2. URL 只保存 HTTP(S) 资料地址，不在创建时抓取远程页面。
        validateWebsiteUrl(project.getWebsiteUrl());
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

    /** 验证可解析的 HTTP(S) 地址；不接受脚本协议或无主机地址。 */
    private void validateWebsiteUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("官网或资料 URL 必须是有效的 HTTP(S) 地址");
        }
    }
}
