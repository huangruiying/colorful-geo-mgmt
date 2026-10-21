package org.huangry.colorful.geo.application.project.model;

import lombok.Data;
import java.util.List;

/** 创建项目输入；仅接收基础资料，ID 与时间由数据库生成。 */
@Data
public class CreateProjectRequest {
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
}
