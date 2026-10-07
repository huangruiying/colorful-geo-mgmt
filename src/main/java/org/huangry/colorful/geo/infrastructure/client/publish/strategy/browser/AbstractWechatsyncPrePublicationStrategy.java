package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser;

import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.AbstractPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.wechatsync.WechatsyncClient;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wechatsync 平台草稿策略的公共执行边界。
 *
 * <p>各平台的 execPrePublish 指定 CLI 平台标识；本类只确认草稿创建结果，
 * 不负责已有草稿的公开发布，也不推测平台未返回的草稿标识。</p>
 *
 * @author huangry
 */
@Slf4j
public abstract class AbstractWechatsyncPrePublicationStrategy extends AbstractPublicationStrategy {

    /** 去除 CLI 终端颜色，避免 ANSI 控制字符影响草稿结果匹配。 */
    private static final Pattern ANSI_COLOR = Pattern.compile("\u001B\\[[0-9;]*m");

    /** 单平台同步必须明确只有一次成功且没有失败。 */
    private static final Pattern SINGLE_SUCCESS = Pattern.compile("(?s).*同步完成:\\h*1\\h+成功,\\h*0\\h+失败\\s*$");

    /** 单平台明确失败时可记录为失败；其余未确认输出仍需人工核对。 */
    private static final Pattern SINGLE_FAILURE = Pattern.compile("(?s).*同步完成:\\h*0\\h+成功,\\h*1\\h+失败\\s*$");

    /**
     * 提交单个平台的内容，仅在 CLI 明确确认草稿时返回成功。
     *
     * @param client   共享的 Wechatsync CLI 客户端
     * @param request  已校验的目标平台、标题和正文
     * @param platform Wechatsync 平台标识
     * @return 草稿创建结果；平台未返回标识或链接时相应字段为空
     */
    protected PublicationResult createDraft(WechatsyncClient client, PlatformPublicationRequest request,
                                            String platform) {
        // 提交目标平台；CLI 进程退出成功不等于平台确认创建草稿。
        log.info("开始创建平台草稿，platformType={}，wechatsyncPlatform={}", platformType(), platform);
        String output = client.sync(platform, request.getTitle(), request.getContent());

        // 只接收当前平台的草稿标记与单平台成功汇总，不把普通同步当作草稿。
        PublicationResult result = parseDraftResult(output, platform);
        log.info("平台草稿已创建，platformType={}，draftUrlPresent={}",
                platformType(), result.getDraftUrl() != null);
        return result;
    }

    /**
     * 解析单平台同步输出，不把未知格式、普通同步或失败结果标为草稿。
     *
     * @param output   CLI 原始输出
     * @param platform Wechatsync 平台标识
     * @return 已确认的草稿状态与可选草稿链接
     */
    PublicationResult parseDraftResult(String output, String platform) {
        // 1. 去除终端颜色，只解析 CLI 的实际同步结果区，不从标题或正文推断草稿状态。
        String text = ANSI_COLOR.matcher(output).replaceAll("");
        int resultStart = text.lastIndexOf("同步结果:");
        if (resultStart < 0) {
            throw draftResultException();
        }
        String resultText = text.substring(resultStart);

        // 平台明确报告零成功、一失败时允许后续重试；未知格式不得冒充确定失败。
        if (SINGLE_FAILURE.matcher(resultText).matches()) {
            throw new PublicationClientException(platformType().getDisplayName() + "草稿创建失败，请检查平台登录态");
        }

        // 2. 锁定目标平台的草稿成功行；编辑链接由平台返回，缺失时保持为空。
        Pattern draftLine = Pattern.compile("(?m)^\\h*✓\\h+" + Pattern.quote(platform)
                + "\\h+\\(草稿\\)\\h*(?:\\R\\h*(https?://\\S+)\\h*)?$");
        Matcher match = draftLine.matcher(resultText);

        // 3. 成功计数仅说明同步操作成功，仍须同时看到目标平台的“草稿”业务标记。
        if (!SINGLE_SUCCESS.matcher(resultText).matches() || !match.find()) {
            throw draftResultException();
        }
        return PublicationResult.builder()
                .status(PublicationTaskStatus.DRAFT_CREATED)
                .draftUrl(match.group(1))
                .message(platformType().getDisplayName() + "草稿已创建，尚未公开发布")
                .build();
    }

    /**
     * 当前扩展的平台策略仅创建草稿；公开发布须另行接入平台专属能力。
     *
     * @param request 已有草稿请求
     * @return 不返回结果，始终抛出未接入异常
     */
    @Override
    protected PublicationResult execPublish(PrePublicationRequest request) {
        throw new PublicationClientException(platformType().getDisplayName() + "草稿公开发布暂未接入");
    }

    /**
     * 返回固定提示，不回传可能包含标题、正文或鉴权信息的 CLI 原始输出。
     *
     * @return 草稿结果无法确认异常
     */
    private PublicationOutcomeUnknownException draftResultException() {
        return new PublicationOutcomeUnknownException("未收到" + platformType().getDisplayName()
                + "草稿成功结果，请检查登录态并核对草稿箱");
    }
}
