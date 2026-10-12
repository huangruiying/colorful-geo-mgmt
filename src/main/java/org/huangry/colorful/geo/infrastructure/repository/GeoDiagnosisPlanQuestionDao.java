package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanQuestionEntity;

/** GeoDiagnosisPlanQuestion 的持久化入口。 */
@Mapper
public interface GeoDiagnosisPlanQuestionDao extends BaseMapper<GeoDiagnosisPlanQuestionEntity> {}
