package org.huangry.colorful.geo.infrastructure.repository;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
/** 模型渠道 DAO；读写数据库配置，业务校验与模型调用由上层负责。 */
@Mapper
public interface GeoLlmChannelDao extends BaseMapper<GeoLlmChannelEntity> {}
