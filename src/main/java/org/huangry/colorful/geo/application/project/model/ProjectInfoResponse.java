package org.huangry.colorful.geo.application.project.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/** 项目基础档案接口响应；展示已保存资料，不参与持久化或任务执行。 */
@Data
public class ProjectInfoResponse {
    /** 项目标识，供后续任务关联。 */
    private Long id;
    /** 项目或产品名称。 */
    private String name;
    /** 官网或资料 URL。 */
    private String websiteUrl;
    /** 所属行业或品类。 */
    private String industry;
    /** 核心功能。 */
    private String coreFeatures;
    /** 目标用户群体。 */
    private String targetAudience;
    /** 别名或品牌名。 */
    private List<String> aliases;
    /** 主要竞品。 */
    private List<String> competitors;
    /** 目标市场。 */
    private String targetMarket;
    /** 目标语言。 */
    private String targetLanguage;
    /** 核心卖点。 */
    private String sellingPoints;
    /** 价格说明。 */
    private String pricing;
    /** 目标关键词。 */
    private List<String> keywords;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
