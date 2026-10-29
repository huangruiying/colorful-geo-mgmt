package org.huangry.colorful.geo.domain.service.content;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.domain.model.ContentPublishRecord;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.ContentPublishRecordStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.model.PageQuery;
import org.huangry.colorful.geo.infrastructure.repository.ContentPublishRecordDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentPublishRecordEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 平台发布记录持久化服务；管理失败重试、确认稿快照与预发布状态迁移，不执行外部调用。
 */
@Service
@Slf4j
public class ContentPublishRecordService extends ServiceImpl<ContentPublishRecordDao, ContentPublishRecordEntity> {

    /**
     * 发布记录列表（分页）查询，最新的在前，可按文章筛选。
     *
     * @param pageQuery 已校验的分页参数
     * @param contentOptimizationRecordId 文章（内容优化记录）ID；为空时不筛选
     * @return 当前页发布记录
     */
    public List<ContentPublishRecord> listContentPublishRecords(PageQuery pageQuery, Long contentOptimizationRecordId) {
        LambdaQueryWrapper<ContentPublishRecordEntity> query = filterByArticle(contentOptimizationRecordId)
                .orderByDesc(ContentPublishRecordEntity::getId)
                .last(pageQuery.limitClause());
        return baseMapper.selectList(query).stream().map(this::convertToContentPublishRecord).toList();
    }

    /**
     * 按列表相同的筛选条件统计发布记录，供列表分页。
     *
     * @param contentOptimizationRecordId 文章（内容优化记录）ID；为空时不筛选
     * @return 发布记录总数
     */
    public long countContentPublishRecords(Long contentOptimizationRecordId) {
        return count(filterByArticle(contentOptimizationRecordId));
    }

    /**
     * 列出有发布记录的文章，每篇一条，标题取该文章最新一条发布记录的标题快照，最新的文章在前。
     * <p>只读 ID、文章 ID 和标题三列，不加载正文。</p>
     *
     * @return 每篇文章最新的一条发布记录（只含 ID、文章 ID、标题）
     */
    public List<ContentPublishRecordEntity> listLatestRecordPerArticle() {
        List<ContentPublishRecordEntity> records = list(new LambdaQueryWrapper<ContentPublishRecordEntity>()
                .select(ContentPublishRecordEntity::getId, ContentPublishRecordEntity::getContentOptimizationRecordId,
                        ContentPublishRecordEntity::getPublicationTitle)
                .orderByDesc(ContentPublishRecordEntity::getId));
        // 按 ID 倒序后每篇文章第一次出现的就是最新一条，后面的同文章记录丢弃
        Set<Long> seenArticles = new HashSet<>();
        return records.stream().filter(record -> seenArticles.add(record.getContentOptimizationRecordId())).toList();
    }

    /**
     * 构造按文章筛选的查询条件，列表和总数共用。
     *
     * @param contentOptimizationRecordId 文章（内容优化记录）ID；为空时不加条件
     * @return 查询条件
     */
    private LambdaQueryWrapper<ContentPublishRecordEntity> filterByArticle(Long contentOptimizationRecordId) {
        return new LambdaQueryWrapper<ContentPublishRecordEntity>().eq(contentOptimizationRecordId != null,
                ContentPublishRecordEntity::getContentOptimizationRecordId, contentOptimizationRecordId);
    }

    /**
     * 删除指定本地发布记录；执行中不可删，平台已有草稿或文章不会被删除。
     *
     * @param id 发布记录 ID
     */
    public void deleteContentPublishRecord(long id) {
        // 以数据库当前状态原子判断，避免查询与删除之间平台任务进入执行中。
        if (baseMapper.deleteNonProcessingRecord(id) == 1) {
            log.info("本地内容发布记录已删除，publishRecordId={}", id);
            return;
        }
        ContentPublishRecordEntity record = baseMapper.selectById(id);
        if (record == null) {
            throw new NoSuchElementException("发布记录不存在");
        }
        throw new IllegalStateException("记录正在预发布或实际发布中，暂不能删除；请先核对平台结果");
    }

    /**
     * 用户确认后创建平台发布记录；明确失败且无草稿信息时先移除旧记录，其他状态不重发。
     *
     * @param contentOptimizationRecordId 内容优化记录标识
     * @param platformType 目标平台
     * @param publicationTitle 用户确认的标题
     * @param publicationContent 用户确认的正文
     * @return 本次是否新建记录
     */
    @Transactional
    public boolean createInitializedRecord(long contentOptimizationRecordId, PublicationPlatformType platformType,
                                           String publicationTitle, String publicationContent) {
        // 1. 仅清理当前内容、当前平台的确定失败结果；删除与新建同事务，防止插入失败后丢失旧记录。
        try {
            int deleted = baseMapper.deleteFailedPrePublishRecord(contentOptimizationRecordId, platformType);
            if (deleted > 0) {
                log.info("内容预发布失败记录已移除，contentOptimizationRecordId={}，platformType={}",
                        contentOptimizationRecordId, platformType);
            }
            // 2. 用本次确认稿重新初始化；成功记录或待核对记录占用唯一键时保持原记录。
            boolean created = baseMapper.createInitializedRecord(contentOptimizationRecordId, platformType,
                    publicationTitle, publicationContent) == 1;
            if (created) {
                log.info("内容发布记录已初始化，contentOptimizationRecordId={}，platformType={}",
                        contentOptimizationRecordId, platformType);
            }
            return created;
        } catch (DuplicateKeyException exception) {
            // 内容与平台唯一键已占用时跳过；其他数据库错误继续向上抛出。
            return false;
        }
    }

    /**
     * 将初始化记录推进到预发布中；未命中说明不能安全地调用平台。
     *
     * @param contentOptimizationRecordId 内容优化记录标识
     * @param platformType 目标平台
     */
    public void startPrePublish(long contentOptimizationRecordId, PublicationPlatformType platformType) {
        if (baseMapper.startPrePublish(contentOptimizationRecordId, platformType) != 1) {
            throw new IllegalStateException("发布记录未能进入预发布中，未调用平台");
        }
        log.info("内容发布记录进入预发布中，contentOptimizationRecordId={}，platformType={}",
                contentOptimizationRecordId, platformType);
    }

    /**
     * 保存平台明确返回的草稿状态和标识，不推测未返回的内容 ID。
     *
     * @param contentOptimizationRecordId 内容优化记录标识
     * @param result 单个平台的标准结果
     */
    public void savePrePublishResult(long contentOptimizationRecordId, PublicationResult result) {
        // 1. 平台结果映射为本地状态；超时结果仍停留在预发布中等待核对。
        ContentPublishRecordStatus status = switch (result.getStatus()) {
            case DRAFT_CREATED -> ContentPublishRecordStatus.PRE_PUBLISHED;
            case PUBLISHED -> ContentPublishRecordStatus.PUBLISHED;
            case FAILED -> ContentPublishRecordStatus.PRE_PUBLISH_FAILED;
            case PUBLISHING -> ContentPublishRecordStatus.PRE_PUBLISHING;
            default -> throw new IllegalStateException("平台返回了不支持的预发布状态");
        };
        String failureReason = status == ContentPublishRecordStatus.PRE_PUBLISHED
                || status == ContentPublishRecordStatus.PUBLISHED ? null : result.getMessage();
        if (failureReason != null && failureReason.length() > 500) {
            failureReason = failureReason.substring(0, 500);
        }

        // 2. 只回写预发布中的记录，不让迟到结果覆盖已有结论。
        int updated = baseMapper.savePrePublishResult(contentOptimizationRecordId, result.getPlatformType(), status.getCode(),
                result.getRemoteContentId(), result.getDraftUrl(), result.getPublishedUrl(), failureReason);

        // 3. 落库失败时阻断流程，提醒调用方先核对平台草稿箱。
        if (updated != 1) {
            throw new IllegalStateException("平台草稿结果未能保存，请核对平台草稿箱与本地发布记录");
        }
        log.info("内容发布记录预发布结果已保存，contentOptimizationRecordId={}，platformType={}，status={}",
                contentOptimizationRecordId, result.getPlatformType(), status);
    }

    /**
     * 按创建顺序列出一篇内容的全部平台发布记录。
     *
     * @param contentOptimizationRecordId 内容优化记录标识
     * @return 各平台独立状态与草稿链接
     */
    public List<ContentPublishRecord> listByContentOptimizationRecordId(long contentOptimizationRecordId) {
        // 按发布记录创建顺序查询，并在返回应用层前剥离 ORM 表实体。
        return lambdaQuery().eq(ContentPublishRecordEntity::getContentOptimizationRecordId,
                        contentOptimizationRecordId)
                .orderByAsc(ContentPublishRecordEntity::getId).list().stream()
                .map(this::convertToContentPublishRecord).toList();
    }

    /**
     * 将数据库记录映射为不含 ORM 注解的领域结果。
     *
     * @param entity 数据库记录
     * @return 平台发布记录领域对象
     */
    private ContentPublishRecord convertToContentPublishRecord(ContentPublishRecordEntity entity) {
        return ContentPublishRecord.builder()
                .id(entity.getId())
                .contentOptimizationRecordId(entity.getContentOptimizationRecordId())
                .platformType(entity.getPlatformType())
                .publicationTitle(entity.getPublicationTitle())
                .publicationContent(entity.getPublicationContent())
                .publishStatus(entity.getPublishStatus())
                .remoteContentId(entity.getRemoteContentId())
                .draftUrl(entity.getDraftUrl())
                .publishedUrl(entity.getPublishedUrl())
                .failureReason(entity.getFailureReason())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
