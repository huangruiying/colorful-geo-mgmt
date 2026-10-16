package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisResultOutputEntity;

/** geo_diagnosis_result_output 持久化入口，提供 BaseMapper 增删改查；报告状态流转由领域服务负责，事务由调用方负责。 */
@Mapper
public interface GeoDiagnosisResultOutputDao extends BaseMapper<GeoDiagnosisResultOutputEntity> {
}
