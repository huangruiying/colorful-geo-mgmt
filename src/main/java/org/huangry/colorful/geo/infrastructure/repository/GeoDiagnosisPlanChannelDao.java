package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanChannelEntity;

/** GeoDiagnosisPlanChannel 的持久化入口。 */
@Mapper
public interface GeoDiagnosisPlanChannelDao extends BaseMapper<GeoDiagnosisPlanChannelEntity> {}
