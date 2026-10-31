package org.huangry.colorful.geo.infrastructure.client.playwright.takeover.verification;

import com.microsoft.playwright.Page;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;

import java.util.Arrays;
import java.util.Optional;

/**
 * 浏览器页面上需要用户本人完成的平台验证有哪些、各自怎么识别。
 * <p>人工验证场景 {@link HumanVerificationTakeover} 用 {@link #detect} 判断页面是否停在验证上，并以验证消失作为接管完成条件；
 * 新增或调整识别只改这里。平台为空的规则对所有平台生效，其余只对所标平台生效。
 * 平台专属规则声明在前，同时命中时优先用它的原因说明。</p>
 * <p>约束：只判断验证是否存在，不识别验证码内容、不操作验证组件；只收录实际遇到并核对过页面特征的验证。</p>
 */
@Getter
@RequiredArgsConstructor
public enum HumanVerificationRule {

	/** 搜狐号对新设备的短信安全验证：打开后台页面会被跳到 clientAuth，页内有腾讯滑块和短信验证码。 */
	SOHU_CLIENT_AUTH(PublicationPlatformType.SOHU, "搜狐号要求对本浏览器做短信安全验证") {
		@Override
		public boolean matches(Page page) {
			return page.url().contains("/mpfe/v4/clientAuth");
		}
	},

	/** 腾讯滑块验证码：以 turing.captcha 域名的 iframe 弹出，可见即视为待验证。 */
	TENCENT_CAPTCHA(null, "平台弹出了滑块验证码") {
		@Override
		public boolean matches(Page page) {
			return page.locator("iframe[src*='turing.captcha']").first().isVisible();
		}
	};

	/** 适用平台；为空表示通用规则。 */
	private final PublicationPlatformType platform;
	/** 展示给用户的验证原因。 */
	private final String reason;

	/**
	 * 判断页面当前是否停在本规则描述的验证上。
	 *
	 * @param page 自动化流程正在使用的页面，只能在创建它的线程调用
	 * @return 命中本规则时为 true
	 */
	public abstract boolean matches(Page page);

	/**
	 * 按通用规则和当前平台的专属规则识别页面上的验证。
	 *
	 * @param page 自动化流程正在使用的页面，只能在创建它的线程调用
	 * @param platform 页面所属平台
	 * @return 第一条命中的规则；没有验证时为空
	 */
	public static Optional<HumanVerificationRule> detect(Page page, PublicationPlatformType platform) {
		return Arrays.stream(values())
				.filter(rule -> rule.platform == null || rule.platform == platform)
				.filter(rule -> rule.matches(page))
				.findFirst();
	}
}
