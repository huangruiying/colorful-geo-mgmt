package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskEntity;

/** geo_diagnosis_task 持久化入口，提供 BaseMapper 增删改查；事务与执行编排由调用方负责。 */
@Mapper
public interface GeoDiagnosisTaskDao extends BaseMapper<GeoDiagnosisTaskEntity> {}
