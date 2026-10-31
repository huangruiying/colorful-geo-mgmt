package org.huangry.colorful.geo.infrastructure.client.playwright.takeover;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 浏览器人工接管的登记表和唯一入口：把服务器上 Playwright 页面的实时画面交给管理页，由用户亲自操作，
 * 直到调用方给出的完成条件满足。
 * <p>调用方（场景，如人工验证）需要人的时候调用 {@link #requestTakeover}：登记后由调用线程一边转发接管窗口的画面与操作，
 * 一边等到完成、放弃或超时。管理页通过 {@link #listTakeovers}、{@link #getTakeover} 发现记录并交互。</p>
 * <p>边界：只负责技术层面的接管，不认识平台和业务，也不判断为什么需要人工、怎样算完成，这些都由调用方传入；
 * 记录只在内存，进程内单例共享。</p>
 */
@Slf4j
@Component
public class BrowserTakeoverRegistry {

	/** 等待期间每轮的间隔：转发操作、确认截图、检查完成条件各一次。 */
	private static final double LOOP_INTERVAL_MILLIS = 100;

	/** 单次接管最长等待时间，单位秒。 */
	@Value("${colorful.geo.browser-takeover.timeout-seconds:600}")
	private long timeoutSeconds;

	private final Map<String, BrowserTakeover> takeovers = new ConcurrentHashMap<>();

	/**
	 * 登记一次人工接管，等用户在接管窗口操作到完成条件满足。
	 * <p>阻塞调用线程直到有结局；期间页面只由调用线程操作。结束后记录从登记表移除。</p>
	 *
	 * @param page 需要人工操作的页面，必须是调用线程创建的
	 * @param title 窗口标题中说明接管对象，如“搜狐号 · 预发布”
	 * @param reason 需要人工的原因，展示给用户
	 * @param completionCondition 判断用户是否已完成，由调用线程每轮检查；页面跳转中读取失败按未完成处理
	 * @return 已完成、已放弃或超时；放弃和超时由调用方按失败处理
	 */
	public BrowserTakeoverOutcome requestTakeover(Page page, String title, String reason,
			Predicate<Page> completionCondition) {
		// 1. 登记接管记录，管理页轮询后弹出接管窗口
		Instant startedAt = Instant.now();
		BrowserTakeover takeover = new BrowserTakeover(UUID.randomUUID().toString(), title, reason,
				startedAt, startedAt.plusSeconds(timeoutSeconds));
		takeovers.put(takeover.getTakeoverId(), takeover);
		log.warn("browser takeover requested, takeoverId={}, title={}, reason={}",
				takeover.getTakeoverId(), title, reason);

		// 2. 在本线程驱动接管窗口直到有结局，结束后移除记录，窗口取画面时得到 404 并关闭
		try (BrowserTakeoverSession session = BrowserTakeoverSession.open(page, takeover)) {
			BrowserTakeoverOutcome outcome = waitForOutcome(page, takeover, session, completionCondition);
			log.info("browser takeover finished, takeoverId={}, title={}, outcome={}",
					takeover.getTakeoverId(), title, outcome);
			return outcome;
		} finally {
			takeovers.remove(takeover.getTakeoverId());
		}
	}

	/**
	 * 列出等待中的接管，按登记先后排序，管理页按顺序逐条弹出。
	 *
	 * @return 等待中的接管
	 */
	public List<BrowserTakeover> listTakeovers() {
		return takeovers.values().stream()
				.sorted(Comparator.comparing(BrowserTakeover::getStartedAt))
				.toList();
	}

	/**
	 * 按标识取一条等待中的接管。
	 *
	 * @param takeoverId 接管标识
	 * @return 等待中的接管
	 * @throws NoSuchElementException 已完成、放弃、超时或不存在
	 */
	public BrowserTakeover getTakeover(String takeoverId) {
		BrowserTakeover takeover = takeovers.get(takeoverId);
		if (takeover == null) {
			throw new NoSuchElementException("人工接管已结束或不存在");
		}
		return takeover;
	}

	/**
	 * 循环转发窗口操作和画面，直到完成条件满足、用户放弃或超时。
	 *
	 * @param page 需要人工操作的页面
	 * @param takeover 接管记录
	 * @param session 接管通道
	 * @param completionCondition 调用方给出的完成条件
	 * @return 已完成、已放弃或超时
	 */
	private BrowserTakeoverOutcome waitForOutcome(Page page, BrowserTakeover takeover, BrowserTakeoverSession session,
			Predicate<Page> completionCondition) {
		while (true) {
			// 放弃和超时优先：用户已放弃时不再把积压的操作作用到页面
			if (takeover.isAbandoned()) {
				return BrowserTakeoverOutcome.ABANDONED;
			}
			if (takeover.isExpired(Instant.now())) {
				return BrowserTakeoverOutcome.TIMED_OUT;
			}
			session.syncScreencast();
			session.dispatchInputs();
			session.acknowledgeFrames();
			if (isCompleted(page, completionCondition)) {
				return BrowserTakeoverOutcome.COMPLETED;
			}
			// 等待期间 Playwright 才会派发截图事件
			page.waitForTimeout(LOOP_INTERVAL_MILLIS);
		}
	}

	/**
	 * 检查完成条件；页面正在跳转、暂时读不到时按未完成处理，下一轮再判断。
	 *
	 * @param page 需要人工操作的页面
	 * @param completionCondition 调用方给出的完成条件
	 * @return 完成条件满足时为 true
	 */
	private boolean isCompleted(Page page, Predicate<Page> completionCondition) {
		try {
			return completionCondition.test(page);
		} catch (PlaywrightException exception) {
			return false;
		}
	}
}
