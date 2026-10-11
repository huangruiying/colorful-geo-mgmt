package org.huangry.colorful.geo.domain.service.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.huangry.colorful.geo.domain.model.LlmChannel;
import org.huangry.colorful.geo.infrastructure.repository.GeoLlmChannelDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.springframework.beans.BeanUtils;
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
    public LlmChannel saveChannel(Long id, LlmChannel channel, String apiKey) {
        // 1. 校验配置，使无效地址和参数在调用前被拒绝。
        validateChannel(channel);
        // 2. 更新必须定位已有渠道，避免编辑失效时变成新建。
        if (id != null && !getChannel(id).getChannelCode().equals(channel.getChannelCode()))
            throw new IllegalArgumentException("渠道编码创建后不可修改");
        GeoLlmChannelEntity entity = new GeoLlmChannelEntity();
        BeanUtils.copyProperties(channel, entity, "id", "createdAt", "updatedAt");
        entity.setId(id);
        // 新建保存输入密钥；编辑留空则保留，输入新值才更新。
        String key = apiKey == null ? null : apiKey.strip();
        if (key != null && !key.isEmpty()) {
            if (key.length() > 8192) throw new IllegalArgumentException("密钥长度不能超过 8192 字符");
            entity.setApiKey(key);
        }
        boolean keyConfigured = entity.getApiKey() != null || (id != null && Boolean.TRUE.equals(getChannel(id).getKeyConfigured()));
        if (channel.getEnabled() && channel.getProtocolType() != LLMProviderType.MOCK && !keyConfigured)
            throw new IllegalArgumentException("启用远程模型前请保存 API 密钥");
        // 3. 保存并回读，时间由数据库维护。
        if (!(id == null ? save(entity) : updateById(entity))) throw new IllegalStateException("模型渠道保存失败");
        return getChannel(entity.getId());
    }

    /** 查询渠道完整配置；不存在时返回明确错误。 */
    public LlmChannel getChannel(long id) {
        GeoLlmChannelEntity entity = getById(id);
        if (entity == null) throw new NoSuchElementException("模型渠道不存在");
        return toChannel(entity);
    }

    /** 按名称或编码查询，按默认优先级排序。 */
    public List<LlmChannel> listChannels(String keyword, int size, long offset) {
        return list(query(keyword).orderByDesc(GeoLlmChannelEntity::getScore).orderByAsc(GeoLlmChannelEntity::getId)
            .last("LIMIT " + size + " OFFSET " + offset)).stream().map(this::toChannel).toList();
    }

    /** 使用列表相同的条件统计总数。 */
    public long countChannels(String keyword) { return count(query(keyword)); }

    /** 只修改启用状态，避免覆盖编辑中的其他参数。 */
    @Transactional
    public LlmChannel changeEnabled(long id, boolean enabled) {
        // 确认记录存在后只更新状态。
        LlmChannel channel = getChannel(id);
        if (enabled && channel.getProtocolType() != LLMProviderType.MOCK && !Boolean.TRUE.equals(channel.getKeyConfigured()))
            throw new IllegalArgumentException("启用远程模型前请保存 API 密钥");
        GeoLlmChannelEntity entity = new GeoLlmChannelEntity();
        entity.setId(id);
        entity.setEnabled(enabled);
        if (!updateById(entity)) throw new IllegalStateException("模型渠道状态更新失败");
        return getChannel(id);
    }

    /** 参数绑定查询，编码及名称使用同一关键词。 */
    private LambdaQueryWrapper<GeoLlmChannelEntity> query(String keyword) {
        return new LambdaQueryWrapper<GeoLlmChannelEntity>().and(keyword != null && !keyword.isBlank(),
            q -> q.like(GeoLlmChannelEntity::getChannelName, keyword).or().like(GeoLlmChannelEntity::getChannelCode, keyword));
    }

    /** 持久化对象转换成独立渠道资料。 */
    private LlmChannel toChannel(GeoLlmChannelEntity entity) {
        LlmChannel channel = new LlmChannel();
        BeanUtils.copyProperties(entity, channel);
        channel.setKeyConfigured(entity.getApiKey() != null && !entity.getApiKey().isBlank());
        return channel;
    }

    /** 校验协议、连接与参数；模拟渠道不要求远程地址。 */
    private void validateChannel(LlmChannel c) {
        // 1. 渠道编码稳定关联配置，不允许空白、特殊字符和超长名称。
        c.setChannelCode(text(c.getChannelCode(), "渠道编码", 100, true));
        if (!c.getChannelCode().matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("渠道编码只能使用字母、数字、下划线和短横线");
        c.setChannelName(text(c.getChannelName(), "渠道名称", 200, true));
        c.setProviderCode(text(c.getProviderCode(), "供应商标识", 100, true));
        if (c.getProtocolType() == null) throw new IllegalArgumentException("请选择调用协议");
        // 2. 远程地址不允许用户凭据、查询串和片段，密钥单独保存且不回传。
        boolean remote = c.getProtocolType() != LLMProviderType.MOCK;
        c.setBaseUrl(text(c.getBaseUrl(), "接口地址", 2048, remote));
        c.setModelName(text(c.getModelName(), "模型标识", 200, remote));
        if (remote) validateUrl(c.getBaseUrl());
        // 3. 默认值与范围统一在领域层处理，页面和调用方遵循相同规则。
        if (c.getTemperature() == null) c.setTemperature(0.3D);
        if (!Double.isFinite(c.getTemperature()) || c.getTemperature() < 0 || c.getTemperature() > 2)
            throw new IllegalArgumentException("生成随机性必须在 0 到 2 之间");
        if (c.getMaxTokens() == null) c.setMaxTokens(1400);
        if (c.getMaxTokens() < 1 || c.getMaxTokens() > 1000000) throw new IllegalArgumentException("输出上限必须在 1 到 1000000 之间");
        if (c.getTimeoutSeconds() == null) c.setTimeoutSeconds(60);
        if (c.getTimeoutSeconds() < 1 || c.getTimeoutSeconds() > 600) throw new IllegalArgumentException("超时必须在 1 到 600 秒之间");
        if (c.getScore() == null) c.setScore(0);
        if (c.getEnabled() == null) c.setEnabled(false);
        if (c.getSslVerify() == null) c.setSslVerify(true);
        if (c.getWebSearchSupported() == null) c.setWebSearchSupported(false);
        if (c.getProtocolType() == LLMProviderType.MOCK && c.getWebSearchSupported())
            throw new IllegalArgumentException("模拟渠道不支持联网搜索");
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
