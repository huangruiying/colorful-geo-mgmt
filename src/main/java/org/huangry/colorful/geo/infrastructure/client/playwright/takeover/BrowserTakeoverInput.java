package org.huangry.colorful.geo.infrastructure.client.playwright.takeover;

import java.util.Map;

/**
 * 用户在接管窗口里的一次鼠标、滚轮或键盘操作，由后台线程转发到服务器上的真实页面。
 * <p>坐标是页面 CSS 像素（前端已按画面缩放换算）；只支持鼠标左键。</p>
 *
 * @param type 操作类型
 * @param x 横坐标，鼠标与滚轮操作使用
 * @param y 纵坐标，鼠标与滚轮操作使用
 * @param deltaX 横向滚动量，仅滚轮使用
 * @param deltaY 纵向滚动量，仅滚轮使用
 * @param text 输入文字，仅 INSERT_TEXT 使用
 * @param key 功能键名称，仅 KEY_PRESS 使用，取值见 {@link #SPECIAL_KEY_CODES}
 */
public record BrowserTakeoverInput(Type type, double x, double y, double deltaX, double deltaY, String text, String key) {

	/** 一次输入文字上限；人工操作只需填验证码、手机号等短文本。 */
	private static final int MAX_TEXT_LENGTH = 100;
	/** 页面坐标上限，超出说明请求不是来自接管窗口。 */
	private static final double MAX_COORDINATE = 10_000;

	/** 允许转发的功能键及其 Windows 虚拟键码；字符输入走 INSERT_TEXT，不转发组合键。 */
	static final Map<String, Integer> SPECIAL_KEY_CODES = Map.of(
			"Backspace", 8, "Tab", 9, "Enter", 13, "Escape", 27, "Delete", 46,
			"ArrowLeft", 37, "ArrowUp", 38, "ArrowRight", 39, "ArrowDown", 40);

	/** 接管窗口支持的操作类型。 */
	public enum Type {
		/** 按下鼠标左键。 */
		MOUSE_PRESSED,
		/** 移动鼠标；按住左键时即为拖动。 */
		MOUSE_MOVED,
		/** 松开鼠标左键。 */
		MOUSE_RELEASED,
		/** 滚动滚轮。 */
		MOUSE_WHEEL,
		/** 在当前焦点处输入文字。 */
		INSERT_TEXT,
		/** 按一次功能键。 */
		KEY_PRESS
	}

	/**
	 * 校验操作能否转发；不合法的操作直接拒绝，不进入页面。
	 *
	 * @throws IllegalArgumentException 类型缺失、坐标越界、文字为空或超长、功能键不在允许范围
	 */
	public void validate() {
		if (type == null) {
			throw new IllegalArgumentException("请指定操作类型");
		}
		if (!(x >= 0 && x <= MAX_COORDINATE && y >= 0 && y <= MAX_COORDINATE)) {
			throw new IllegalArgumentException("操作坐标超出页面范围");
		}
		if (type == Type.INSERT_TEXT && (text == null || text.isEmpty() || text.length() > MAX_TEXT_LENGTH)) {
			throw new IllegalArgumentException("输入文字需为 1～" + MAX_TEXT_LENGTH + " 个字符");
		}
		if (type == Type.KEY_PRESS && !SPECIAL_KEY_CODES.containsKey(key)) {
			throw new IllegalArgumentException("不支持的按键");
		}
	}
}
