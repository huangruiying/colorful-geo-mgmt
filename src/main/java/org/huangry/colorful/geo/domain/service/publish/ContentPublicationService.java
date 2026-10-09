package org.huangry.colorful.geo.domain.service.publish;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.PublicationPlatformStrategyRouter;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 内容投放服务，分别编排创建草稿和发布已有草稿。
 * <p>创建草稿按平台去重后串行执行；不自动重试，不负责内容审核或任务持久化。</p>
 * @author huangry
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentPublicationService {
    private final PublicationPlatformStrategyRouter strategyRouter;

    /**
     * 将同一内容逐个平台保存为草稿，某个平台失败不阻断后续平台。
     * @param request 使用统一标题和正文的批量请求
     * @return 各平台草稿创建结果
     */
    public List<PublicationResult> prePublishContent(PublicationRequest request) {
        // 1. 公共参数错误直接拒绝整批，避免部分平台执行后才发现输入不合法。
        validateRequest(request);
        var platforms = new LinkedHashSet<>(request.getPlatformTypes());
        List<PublicationResult> results = new ArrayList<>();
        // 2. 串行创建草稿，避免同一批次争用浏览器登录上下文。
        for (PublicationPlatformType platform : platforms) {
            PlatformPublicationRequest single = PlatformPublicationRequest.builder()
                    .platformType(platform)
                    .title(request.getTitle()).content(request.getContent()).build();
            results.add(executePlatform(platform, () -> strategyRouter.prePublish(single)));
        }
        // 3. 保留各平台独立结果，不将部分成功合并为整批成功。
        return List.copyOf(results);
    }

    /**
     * 将已经存在的平台草稿公开发布，不再次创建草稿。
     * @param request 平台和已有草稿标识
     * @return 单平台发布结果；不确定结果不自动重试
     */
    public PublicationResult publishDraft(PrePublicationRequest request) {
        if (request == null || request.getPlatformType() == null
                || request.getRemoteContentId() == null || request.getRemoteContentId().isBlank()) {
            throw new PublicationClientException("草稿平台和远程草稿标识不能为空");
        }
        return executePlatform(request.getPlatformType(), () -> strategyRouter.publish(request));
    }

    /**
     * 校验整批请求的必填字段；所有校验完成前不访问外部平台。
     * @param request 多平台内容请求
     */
    private void validateRequest(PublicationRequest request) {
        if (request == null || request.getPlatformTypes() == null || request.getPlatformTypes().isEmpty()
                || request.getPlatformTypes().stream().anyMatch(java.util.Objects::isNull)) {
            throw new PublicationClientException("投放平台列表不能为空且不能包含空平台");
        }
        if (request.getTitle() == null || request.getTitle().isBlank()
                || request.getContent() == null || request.getContent().isBlank()) {
            throw new PublicationClientException("标题和正文不能为空");
        }
    }

    /**
     * 隔离单个平台的失败；不确定状态不伪装成成功，也不自动重试。
     * @param platform 目标平台
     * @param execution 单个平台的实际动作
     * @return 平台独立结果
     */
    private PublicationResult executePlatform(PublicationPlatformType platform,
                                              java.util.function.Supplier<PublicationResult> execution) {
        try {
            return execution.get();
        } catch (PublicationOutcomeUnknownException exception) {
            // 已经触发发布的超时保留待核对状态，禁止把它归类成确定失败后自动重试。
            log.warn("平台发布结果待核对，platformType={}，reason={}", platform, exception.getMessage());
            return PublicationResult.builder().platformType(platform)
                    .status(PublicationTaskStatus.PUBLISHING).message(exception.getMessage()).build();
        } catch (PublicationClientException exception) {
            // 仅把登录、校验、未接入等确定失败归入可重试的预发布失败状态。
            log.warn("平台投放未完成，platformType={}，reason={}", platform, exception.getMessage());
            return PublicationResult.builder().platformType(platform)
                    .status(PublicationTaskStatus.FAILED).message(exception.getMessage()).build();
        } catch (RuntimeException exception) {
            // 未知异常可能发生在提交之后，不能当作确定失败开放重试。
            log.error("平台投放异常，platformType={}，exceptionType={}",
                    platform, exception.getClass().getSimpleName());
            return PublicationResult.builder().platformType(platform)
                    .status(PublicationTaskStatus.PUBLISHING)
                    .message("平台执行异常，结果未确认，请核对平台内容后处理，勿直接重试").build();
        }
    }
}
