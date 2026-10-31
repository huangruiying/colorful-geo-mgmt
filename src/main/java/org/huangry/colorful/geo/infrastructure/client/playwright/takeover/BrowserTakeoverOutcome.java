package org.huangry.colorful.geo.infrastructure.client.playwright.takeover;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 一次浏览器人工接管的结局，由调用方决定继续执行还是按失败处理。
 */
@Getter
@RequiredArgsConstructor
public enum BrowserTakeoverOutcome {

	/** 场景判断页面不需要人工，未发起接管；只由场景入口返回，框架的接管本身不会返回它。 */
	NOT_REQUIRED("无需人工接管"),

	/** 用户操作后，调用方给出的完成条件已满足，可以继续。 */
	COMPLETED("人工接管已完成"),

	/** 用户在接管窗口点了放弃。 */
	ABANDONED("已放弃人工接管"),

	/** 超过等待期限仍未完成。 */
	TIMED_OUT("等待人工接管超时");

	/** 结局说明，失败时写入业务失败原因。 */
	private final String description;

	/**
	 * 判断调用方能否继续执行原流程。
	 *
	 * @return 无需接管或已完成时为 true；放弃、超时为 false
	 */
	public boolean canContinue() {
		return this == NOT_REQUIRED || this == COMPLETED;
	}
}
