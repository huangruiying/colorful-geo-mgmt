package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanExecRecordEntity;

/** geo_diagnosis_plan_exec_record 持久化入口，使用 BaseMapper 查询和写入，不编排模型调用。 */
@Mapper
public interface GeoDiagnosisPlanExecRecordDao extends BaseMapper<GeoDiagnosisPlanExecRecordEntity> {}

