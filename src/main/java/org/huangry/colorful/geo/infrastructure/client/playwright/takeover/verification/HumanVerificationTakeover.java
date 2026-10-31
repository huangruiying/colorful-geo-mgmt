package org.huangry.colorful.geo.infrastructure.client.playwright.takeover.verification;

import com.microsoft.playwright.Page;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverOutcome;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverRegistry;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 浏览器人工接管的“人工验证”场景：页面停在平台验证（短信安全验证、滑块等）上时请求人工接管，验证消失即算完成。
 * <p>平台客户端在打开页面、保存等关键步骤调用 {@link #ensureHumanVerified}；用 {@link HumanVerificationRule} 识别验证，
 * 没有验证直接返回，不发起接管。</p>
 * <p>边界：只识别验证是否存在，不识别验证码内容、不操作验证组件，验证由用户本人在接管窗口完成；
 * 失败文案、写回登录态等后续动作由调用方决定。</p>
 */
@Component
public class HumanVerificationTakeover {

	@Resource
	private BrowserTakeoverRegistry takeoverRegistry;

	/**
	 * 确认页面不在验证上；在验证上时请求人工接管，等用户完成验证。
	 *
	 * @param page 自动化流程正在使用的页面，必须是调用线程创建的
	 * @param platform 页面所属平台，用于选取专属识别规则和窗口标题
	 * @param businessName 正在执行的业务名称，如“预发布”，用于窗口标题
	 * @return 无需验证、已完成、已放弃或超时；放弃和超时由调用方按失败处理
	 */
	public BrowserTakeoverOutcome ensureHumanVerified(Page page, PublicationPlatformType platform, String businessName) {
		Optional<HumanVerificationRule> rule = HumanVerificationRule.detect(page, platform);
		if (rule.isEmpty()) {
			return BrowserTakeoverOutcome.NOT_REQUIRED;
		}
		// 验证消失即算完成：用户提交后平台会跳离验证页或关闭验证组件
		return takeoverRegistry.requestTakeover(page, platform.getDisplayName() + " · " + businessName,
				rule.get().getReason(), current -> HumanVerificationRule.detect(current, platform).isEmpty());
	}
}
