package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/** geo_project 表映射；由 DAO 保存基础档案，列表列通过 JSON 类型处理器读写。 */
@Data
@TableName(value = "geo_project", autoResultMap = true)
public class ProjectEntity {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
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
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> aliases;
    /** 主要竞品。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
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
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> keywords;
    /** 数据库创建时间。 */
    private LocalDateTime createdAt;
    /** 数据库更新时间。 */
    private LocalDateTime updatedAt;
}
