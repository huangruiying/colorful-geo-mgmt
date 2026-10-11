package org.huangry.colorful.geo.domain.service.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.huangry.colorful.geo.infrastructure.repository.GeoLlmChannelDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;

/** 模型渠道领域服务；保存、查询与启停配置，不测试远程模型。 */
@Service
public class GeoLlmChannelService extends ServiceImpl<GeoLlmChannelDao, GeoLlmChannelEntity> {
    /** 校验并保存渠道；更新时保持编码和系统字段不变。 */
    @Transactional
    public GeoLlmChannelEntity saveChannel(Long id, GeoLlmChannelEntity channel) {
        // 1. 校验配置，使无效地址和参数在调用前被拒绝。
        validateChannel(channel);
        // 2. 更新必须定位已有渠道，避免编辑失效时变成新建。
        GeoLlmChannelEntity existing = id == null ? null : getById(id);
        if (id != null && existing == null) throw new NoSuchElementException("模型渠道不存在");
        if (existing != null && !existing.getChannelCode().equals(channel.getChannelCode()))
            throw new IllegalArgumentException("渠道编码创建后不可修改");
        channel.setId(id);
        channel.setCreatedAt(null);
        channel.setUpdatedAt(null);
        // 新建保存输入密钥；编辑留空则保留，输入新值才更新。
        String key = channel.getApiKey() == null ? null : channel.getApiKey().strip();
        if (key != null && key.length() > 8192) {
            throw new IllegalArgumentException("密钥长度不能超过 8192 字符");
        }
        channel.setApiKey(key == null || key.isEmpty() ? null : key);
        boolean keyConfigured = channel.getApiKey() != null
            || (existing != null && existing.getApiKey() != null && !existing.getApiKey().isBlank());
        if (channel.getEnabled() && channel.getProtocolType() != LLMProviderType.MOCK && !keyConfigured)
            throw new IllegalArgumentException("启用远程模型前请保存 API 密钥");
        // 3. 保存并回读，时间由数据库维护。
        boolean saved = id == null ? save(channel) : updateById(channel);
        if (!saved) {
            throw new IllegalStateException("模型渠道保存失败");
        }
        return getById(channel.getId());
    }

    /** 按名称或编码查询，按默认优先级排序。 */
    public List<GeoLlmChannelEntity> listChannels(String keyword, int size, long offset) {
        return list(query(keyword).orderByDesc(GeoLlmChannelEntity::getScore).orderByAsc(GeoLlmChannelEntity::getId)
            .last("LIMIT " + size + " OFFSET " + offset));
    }

    /** 使用列表相同的条件统计总数。 */
    public long countChannels(String keyword) { return count(query(keyword)); }

    /** 只修改启用状态，避免覆盖编辑中的其他参数。 */
    @Transactional
    public GeoLlmChannelEntity changeEnabled(long id, boolean enabled) {
        // 确认记录存在后只更新状态。
        GeoLlmChannelEntity channel = getById(id);
        if (channel == null) throw new NoSuchElementException("模型渠道不存在");
        if (enabled && channel.getProtocolType() != LLMProviderType.MOCK && (channel.getApiKey() == null || channel.getApiKey().isBlank()))
            throw new IllegalArgumentException("启用远程模型前请保存 API 密钥");
        GeoLlmChannelEntity entity = new GeoLlmChannelEntity();
        entity.setId(id);
        entity.setEnabled(enabled);
        if (!updateById(entity)) throw new IllegalStateException("模型渠道状态更新失败");
        return getById(id);
    }

    /** 参数绑定查询，编码及名称使用同一关键词。 */
    private LambdaQueryWrapper<GeoLlmChannelEntity> query(String keyword) {
        return new LambdaQueryWrapper<GeoLlmChannelEntity>().and(keyword != null && !keyword.isBlank(),
            q -> q.like(GeoLlmChannelEntity::getChannelName, keyword).or().like(GeoLlmChannelEntity::getChannelCode, keyword));
    }

    /** 校验协议、连接与参数；模拟渠道不要求远程地址。 */
    private void validateChannel(GeoLlmChannelEntity channel) {
        // 1. 渠道编码稳定关联配置，不允许空白、特殊字符和超长名称。
        channel.setChannelCode(text(channel.getChannelCode(), "渠道编码", 100, true));
        if (!channel.getChannelCode().matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("渠道编码只能使用字母、数字、下划线和短横线");
        channel.setChannelName(text(channel.getChannelName(), "渠道名称", 200, true));
        channel.setProviderCode(text(channel.getProviderCode(), "供应商标识", 100, true));
        if (channel.getProtocolType() == null) throw new IllegalArgumentException("请选择调用协议");
        // 2. 远程地址不允许用户凭据、查询串和片段，密钥单独保存且不回传。
        boolean remote = channel.getProtocolType() != LLMProviderType.MOCK;
        channel.setBaseUrl(text(channel.getBaseUrl(), "接口地址", 2048, remote));
        channel.setModelName(text(channel.getModelName(), "模型标识", 200, remote));
        if (remote) validateUrl(channel.getBaseUrl());
        // 3. 默认值与范围统一在领域层处理，页面和调用方遵循相同规则。
        if (channel.getTemperature() == null) channel.setTemperature(0.3D);
        if (!Double.isFinite(channel.getTemperature()) || channel.getTemperature() < 0 || channel.getTemperature() > 2)
            throw new IllegalArgumentException("生成随机性必须在 0 到 2 之间");
        if (channel.getMaxTokens() == null) channel.setMaxTokens(1400);
        if (channel.getMaxTokens() < 1 || channel.getMaxTokens() > 1000000) throw new IllegalArgumentException("输出上限必须在 1 到 1000000 之间");
        if (channel.getTimeoutSeconds() == null) channel.setTimeoutSeconds(60);
        if (channel.getTimeoutSeconds() < 1 || channel.getTimeoutSeconds() > 600) throw new IllegalArgumentException("超时必须在 1 到 600 秒之间");
        if (channel.getScore() == null) channel.setScore(0);
        if (channel.getEnabled() == null) channel.setEnabled(false);
        if (channel.getSslVerify() == null) channel.setSslVerify(true);
    }

    /** 清理输入并限制字段长度。 */
    private String text(String value, String label, int limit, boolean required) {
        String result = value == null ? null : value.strip();
        if (required && (result == null || result.isEmpty())) throw new IllegalArgumentException("请填写" + label);
        if (result != null && result.length() > limit) throw new IllegalArgumentException(label + "最多 " + limit + " 字符");
        return result;
    }

    /** 检查 HTTP(S) 接口根地址，防止凭据混入可展示地址。 */
    private void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || !List.of("http", "https").contains(uri.getScheme())
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("接口地址必须是无凭据、查询参数和片段的 HTTP(S) 地址");
        }
    }
}
