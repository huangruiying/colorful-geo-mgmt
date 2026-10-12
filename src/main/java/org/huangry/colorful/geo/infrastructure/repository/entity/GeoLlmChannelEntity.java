package org.huangry.colorful.geo.infrastructure.repository.entity;

import lombok.Data;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;

/** 模型渠道表映射；承载服务端连接、凭据与优先级，不执行远程调用。 */
@Data
@TableName("geo_llm_channel")
public class GeoLlmChannelEntity {
    /** 数据库生成的渠道标识。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 唯一渠道编码，创建后不可修改。 */
    private String channelCode;
    /** 页面展示名称。 */
    private String channelName;
    /** 供应商标识。 */
    private String providerCode;
    /** 调用协议。 */
    private LLMProtocolType protocolType;
    /** 接口根地址。 */
    private String baseUrl;
    /** 模型标识。 */
    private String modelName;
    /** 服务端调用密钥；不写入日志。 */
    @lombok.ToString.Exclude
    private String apiKey;
    /** 生成随机性。 */
    private Double temperature;
    /** 输出 token 上限。 */
    private Integer maxTokens;
    /** 超时秒数。 */
    private Integer timeoutSeconds;
    /** 是否校验证书。 */
    private Boolean sslVerify;
    /** 默认选择优先级，越大越优先。 */
    private Integer score;
    /** 是否启用。 */
    private Boolean enabled;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
