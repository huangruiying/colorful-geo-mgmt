package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.xiaohongshu;

import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.AbstractPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.springframework.stereotype.Component;

/**
 * 小红书长文的预发布策略：预发布只确认系统已保存确认稿，不在小红书创建草稿；发布时再写入并发布。
 *
 * <p><b>为什么不建草稿（2026-10-30 实测结论，实现发布前必读）：</b>小红书网页版草稿只存在当前浏览器的本地数据库
 * （IndexedDB {@code draft-database-v1}），自动保存和“暂存离开”都不向服务器发请求；草稿箱提示原文
 * “草稿存储于当前使用的浏览器本地，在清除浏览器数据时会被删除”。系统每次用临时浏览器，写完即关闭，
 * 草稿随之消失，用户的电脑和 App 里也永远看不到，因此原先“建草稿后返回预发布成功”是假成功，已改为本策略。</p>
 *
 * <p><b>确认稿在哪：</b>标题与正文已由上层写入 content_publish_record 的 publication_title / publication_content，
 * 本策略返回成功即表示“确认稿已在系统中，待发布”，remoteContentId 与 draftUrl 均为空。</p>
 *
 * <p><b>以后实现 execPublish 的思路：</b></p>
 * <ol>
 *     <li>按 PrePublicationRequest 取不到正文（只有平台和远程 ID），需由上层按发布记录读取确认稿标题和正文后传入，
 *     或调整发布入参；本平台没有可用的远程草稿 ID。</li>
 *     <li>打开写长文编辑器并写入：复用 {@code XiaohongshuBrowserClient} 的步骤（深链
 *     {@code creator.xiaohongshu.com/publish/publish?from=menu&target=article} →“新的创作”{@code button.new-btn} →
 *     标题 {@code textarea[placeholder="输入标题"]} → 正文 {@code div.tiptap.ProseMirror}），不再点“暂存离开”。</li>
 *     <li>长文页的“发布”按钮及发布后的确认方式尚未实测（可能先进入“下一步”或长文合集设置），需先探查；
 *     发布是真实服务端动作，要以接口响应或已发布笔记列表确认，并评估风控与人工验证（见
 *     {@code .doc/20261029_login_use_config/人工验证框架-详细设计.md}）。</li>
 * </ol>
 * <p>其他参考：{@code .doc/平台发文入口与选择器.md} 的“小红书 XIAOHONGSHU”一节。</p>
 */
@Component
public class XiaohongshuPublicationStrategy extends AbstractPublicationStrategy {

	/**
	 * 返回小红书平台。
	 *
	 * @return 小红书平台
	 */
	@Override
	public PublicationPlatformType platformType() {
		return PublicationPlatformType.XIAOHONGSHU;
	}

	/**
	 * 确认稿已由上层保存到发布记录，这里不打开浏览器、不在小红书创建草稿，直接返回预发布成功。
	 *
	 * @param request 目标平台、标题和正文（已由上层校验并保存）
	 * @return 预发布成功，远程 ID 与草稿链接为空，说明草稿保存在系统中
	 */
	@Override
	protected PublicationResult execPrePublish(PlatformPublicationRequest request) {
		return PublicationResult.builder()
				.status(PublicationTaskStatus.DRAFT_CREATED)
				.message("小红书没有云端草稿，确认稿已保存在系统中，发布时将直接写入并发布")
				.build();
	}

	/**
	 * 小红书长文公开发布尚未接入，禁止把确认稿误标成已发布；实现思路见类注释。
	 *
	 * @param request 平台与远程标识（本平台没有可用的远程草稿 ID）
	 * @return 不返回结果，始终抛出未接入异常
	 */
	@Override
	protected PublicationResult execPublish(PrePublicationRequest request) {
		throw new PublicationClientException("小红书公开发布暂未接入");
	}
}
