package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanExecRecordResultEntity;

/** geo_diagnosis_plan_exec_record_result 持久化入口，提供 BaseMapper 增删改查；采样唯一性由数据库约束，事务由调用方负责。 */
@Mapper
public interface GeoDiagnosisPlanExecRecordResultDao extends BaseMapper<GeoDiagnosisPlanExecRecordResultEntity> {}
