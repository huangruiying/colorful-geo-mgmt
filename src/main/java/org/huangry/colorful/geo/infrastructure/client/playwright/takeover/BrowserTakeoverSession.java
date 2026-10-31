package org.huangry.colorful.geo.infrastructure.client.playwright.takeover;

import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 服务器页面与接管窗口之间的画面和操作通道，基于 Chromium 开发者协议（CDP）。
 * <p>画面：窗口在看时用 Page.startScreencast 截图，每帧写入接管记录；没人看时停止。
 * 操作：把窗口放入的鼠标、滚轮、键盘操作用 Input.dispatch* 作用在真实页面。</p>
 * <p>生命周期：由等待人工接管的后台线程打开、驱动和关闭，所有方法只能在该线程调用；
 * 截图事件也只在该线程等待页面时派发。</p>
 */
@Slf4j
final class BrowserTakeoverSession implements AutoCloseable {

	private final CDPSession cdpSession;
	private final BrowserTakeover takeover;
	/** 已收到、尚未确认的截图帧；不确认时浏览器不再发送下一帧。 */
	private final Queue<Integer> unacknowledgedFrames = new ArrayDeque<>();
	private boolean screencasting;
	/** 左键是否处于按下状态；按住时的移动要带上按键，页面才会当成拖动。 */
	private boolean leftButtonPressed;

	private BrowserTakeoverSession(CDPSession cdpSession, BrowserTakeover takeover) {
		this.cdpSession = cdpSession;
		this.takeover = takeover;
	}

	/**
	 * 为需要人工操作的页面打开 CDP 通道并订阅截图事件，此时还不截图。
	 *
	 * @param page 需要人工操作的页面
	 * @param takeover 对应的接管记录
	 * @return 接管通道
	 */
	static BrowserTakeoverSession open(Page page, BrowserTakeover takeover) {
		CDPSession cdpSession = page.context().newCDPSession(page);
		BrowserTakeoverSession session = new BrowserTakeoverSession(cdpSession, takeover);
		cdpSession.on("Page.screencastFrame", session::receiveFrame);
		return session;
	}

	/** 按窗口是否在看开始或停止截图。 */
	void syncScreencast() {
		boolean viewerActive = takeover.isViewerActive();
		if (viewerActive && !screencasting) {
			JsonObject options = new JsonObject();
			options.addProperty("format", "jpeg");
			options.addProperty("quality", 60);
			options.addProperty("maxWidth", 1280);
			options.addProperty("maxHeight", 900);
			cdpSession.send("Page.startScreencast", options);
			screencasting = true;
		} else if (!viewerActive && screencasting) {
			cdpSession.send("Page.stopScreencast");
			screencasting = false;
		}
	}

	/** 确认已收到的截图帧，让浏览器继续推送页面变化。 */
	void acknowledgeFrames() {
		Integer frameSessionId;
		while ((frameSessionId = unacknowledgedFrames.poll()) != null) {
			JsonObject ack = new JsonObject();
			ack.addProperty("sessionId", frameSessionId);
			cdpSession.send("Page.screencastFrameAck", ack);
		}
	}

	/** 按先后顺序把窗口放入的操作全部作用到页面。 */
	void dispatchInputs() {
		BrowserTakeoverInput input;
		while ((input = takeover.pollInput()) != null) {
			dispatchInput(input);
		}
	}

	/**
	 * 截图事件回调：只记录画面和待确认的帧，确认放到后台线程主循环里发送。
	 *
	 * @param event Page.screencastFrame 事件
	 */
	private void receiveFrame(JsonObject event) {
		JsonObject metadata = event.getAsJsonObject("metadata");
		takeover.updateFrame(event.get("data").getAsString(),
				metadata.get("deviceWidth").getAsInt(), metadata.get("deviceHeight").getAsInt());
		unacknowledgedFrames.add(event.get("sessionId").getAsInt());
	}

	/**
	 * 把一次操作转成 CDP 输入事件。
	 *
	 * @param input 已校验的用户操作
	 */
	private void dispatchInput(BrowserTakeoverInput input) {
		switch (input.type()) {
			case MOUSE_PRESSED -> {
				leftButtonPressed = true;
				dispatchMouseEvent("mousePressed", input, "left", 1, 1);
			}
			case MOUSE_RELEASED -> {
				leftButtonPressed = false;
				dispatchMouseEvent("mouseReleased", input, "left", 0, 1);
			}
			case MOUSE_MOVED -> dispatchMouseEvent("mouseMoved", input,
					leftButtonPressed ? "left" : "none", leftButtonPressed ? 1 : 0, 0);
			case MOUSE_WHEEL -> {
				JsonObject event = mouseEvent("mouseWheel", input, "none", 0, 0);
				event.addProperty("deltaX", input.deltaX());
				event.addProperty("deltaY", input.deltaY());
				cdpSession.send("Input.dispatchMouseEvent", event);
			}
			case INSERT_TEXT -> {
				JsonObject event = new JsonObject();
				event.addProperty("text", input.text());
				cdpSession.send("Input.insertText", event);
			}
			case KEY_PRESS -> {
				dispatchKeyEvent("keyDown", input.key());
				dispatchKeyEvent("keyUp", input.key());
			}
		}
	}

	/**
	 * 发送一次鼠标事件。
	 *
	 * @param type CDP 鼠标事件类型
	 * @param input 用户操作（取坐标）
	 * @param button 触发按键
	 * @param buttons 事件发生时按住的按键位掩码，左键为 1
	 * @param clickCount 点击次数，移动时为 0
	 */
	private void dispatchMouseEvent(String type, BrowserTakeoverInput input, String button, int buttons, int clickCount) {
		cdpSession.send("Input.dispatchMouseEvent", mouseEvent(type, input, button, buttons, clickCount));
	}

	/**
	 * 组装鼠标事件参数。
	 *
	 * @param type CDP 鼠标事件类型
	 * @param input 用户操作（取坐标）
	 * @param button 触发按键
	 * @param buttons 按住的按键位掩码
	 * @param clickCount 点击次数
	 * @return CDP 参数
	 */
	private static JsonObject mouseEvent(String type, BrowserTakeoverInput input, String button, int buttons,
			int clickCount) {
		JsonObject event = new JsonObject();
		event.addProperty("type", type);
		event.addProperty("x", input.x());
		event.addProperty("y", input.y());
		event.addProperty("button", button);
		event.addProperty("buttons", buttons);
		event.addProperty("clickCount", clickCount);
		return event;
	}

	/**
	 * 发送一次功能键事件；回车同时带上换行字符，表单才会提交。
	 *
	 * @param type keyDown 或 keyUp
	 * @param key 已校验的功能键名称
	 */
	private void dispatchKeyEvent(String type, String key) {
		JsonObject event = new JsonObject();
		event.addProperty("type", type);
		event.addProperty("key", key);
		event.addProperty("code", key);
		event.addProperty("windowsVirtualKeyCode", BrowserTakeoverInput.SPECIAL_KEY_CODES.get(key));
		if ("keyDown".equals(type) && "Enter".equals(key)) {
			event.addProperty("text", "\r");
		}
		cdpSession.send("Input.dispatchKeyEvent", event);
	}

	/** 停止截图并断开 CDP 通道；页面可能已关闭，收尾失败只记录，不影响原业务的结局。 */
	@Override
	public void close() {
		try {
			// ===== 1. 先停截图，避免断开后浏览器继续推帧 =====
			if (screencasting) {
				cdpSession.send("Page.stopScreencast");
			}
			// ===== 2. 断开通道，释放本次接管的 CDP 资源 =====
			cdpSession.detach();
		} catch (PlaywrightException exception) {
			log.warn("browser takeover session close failed, takeoverId={}", takeover.getTakeoverId(), exception);
		}
	}
}
