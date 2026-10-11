package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.huangry.colorful.geo.infrastructure.repository.entity.ProjectEntity;

/** 项目 DAO；提供基础档案读写，业务校验与任务编排不在此层执行。 */
@Mapper
public interface ProjectDao extends BaseMapper<ProjectEntity> {
}
