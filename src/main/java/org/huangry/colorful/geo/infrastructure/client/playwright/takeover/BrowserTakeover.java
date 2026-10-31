package org.huangry.colorful.geo.infrastructure.client.playwright.takeover;

import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次进行中的浏览器人工接管：登记信息，以及接管窗口与后台线程之间交换的画面和操作。
 * <p>后台线程（持有浏览器页面）写入画面、取出操作、判断结局；HTTP 线程只读画面、放入操作、标记放弃。
 * 两边不共享 Playwright 对象，因为 Playwright 对象只能在创建它的线程使用。</p>
 * <p>只存在于内存：页面对象无法跨进程保存，服务重启后记录随浏览器一起消失。</p>
 */
public final class BrowserTakeover {

	/** 窗口超过这么久没来取画面，视为已关闭，后台线程停止截图。 */
	private static final Duration VIEWER_IDLE = Duration.ofSeconds(5);
	/** 尚未转发的操作上限；页面卡住时拒绝继续堆积。 */
	private static final int MAX_QUEUED_INPUTS = 200;

	/** 接管标识（随机 UUID），不是数据库 ID。 */
	@Getter
	private final String takeoverId;
	/** 窗口标题中说明接管对象，如“搜狐号 · 预发布”，由调用方给出。 */
	@Getter
	private final String title;
	/** 需要人工的原因，由调用方给出。 */
	@Getter
	private final String reason;
	@Getter
	private final Instant startedAt;
	/** 等待期限，到期后按超时结束。 */
	@Getter
	private final Instant expiresAt;

	private final Queue<BrowserTakeoverInput> queuedInputs = new ConcurrentLinkedQueue<>();
	private final AtomicLong frameSequence = new AtomicLong();
	private volatile BrowserTakeoverFrame latestFrame;
	private volatile Instant lastViewedAt = Instant.EPOCH;
	@Getter
	private volatile boolean abandoned;

	/**
	 * 登记一次接管。
	 *
	 * @param takeoverId 接管标识
	 * @param title 窗口标题中的接管对象
	 * @param reason 需要人工的原因
	 * @param startedAt 登记时间
	 * @param expiresAt 等待期限
	 */
	BrowserTakeover(String takeoverId, String title, String reason, Instant startedAt, Instant expiresAt) {
		this.takeoverId = takeoverId;
		this.title = title;
		this.reason = reason;
		this.startedAt = startedAt;
		this.expiresAt = expiresAt;
	}

	/**
	 * 接管窗口读取最新一帧；同时记下窗口仍在查看，后台线程据此开始或继续截图。
	 *
	 * @return 最新画面；截图刚开始、第一帧还没到时为空
	 */
	public Optional<BrowserTakeoverFrame> readLatestFrame() {
		lastViewedAt = Instant.now();
		return Optional.ofNullable(latestFrame);
	}

	/**
	 * 放入一次用户操作，等后台线程转发到页面。
	 *
	 * @param input 用户操作
	 * @throws IllegalArgumentException 操作不合法，或积压的操作过多
	 */
	public void addInput(BrowserTakeoverInput input) {
		input.validate();
		if (queuedInputs.size() >= MAX_QUEUED_INPUTS) {
			throw new IllegalArgumentException("页面暂未响应，请稍后再操作");
		}
		queuedInputs.add(input);
	}

	/** 用户放弃接管；后台线程下一轮看到后结束等待，由调用方按失败处理原业务。 */
	public void abandon() {
		abandoned = true;
	}

	/**
	 * 后台线程写入新截到的一帧，只保留最新一帧。
	 *
	 * @param imageBase64 JPEG 图片 Base64
	 * @param width 页面宽度
	 * @param height 页面高度
	 */
	void updateFrame(String imageBase64, int width, int height) {
		latestFrame = new BrowserTakeoverFrame(imageBase64, width, height, frameSequence.incrementAndGet());
	}

	/**
	 * 后台线程按先后顺序取出一次待转发的操作。
	 *
	 * @return 最早的操作；没有时为空
	 */
	BrowserTakeoverInput pollInput() {
		return queuedInputs.poll();
	}

	/**
	 * 判断接管窗口是否仍在查看；没人看时不截图，避免空耗带宽。
	 *
	 * @return 最近几秒内取过画面时为 true
	 */
	boolean isViewerActive() {
		return lastViewedAt.isAfter(Instant.now().minus(VIEWER_IDLE));
	}

	/**
	 * 判断是否已过等待期限。
	 *
	 * @param now 当前时间
	 * @return 已到期时为 true
	 */
	boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}
}
