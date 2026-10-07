package org.huangry.colorful.geo.infrastructure.client.wechatsync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Wechatsync CLI 执行客户端，统一管理桥接凭证、临时正文和进程生命周期。
 *
 * <p>入口 sync 先检查平台登录状态，再返回有限长度的 CLI 同步输出；平台策略负责业务成功判定。
 * 登录查询超时可唤醒扩展并重查一次；正文同步不重试，也不保证公开发布。</p>
 *
 * @author huangry
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatsyncClient {
	/** CLI 平台列表行：平台标识、登录结论及可选账号名。 */
	private static final Pattern LOGIN_STATUS_LINE = Pattern.compile(
			"^\\s*([a-z0-9-]+)\\s+.+?\\s+([✓✗])\\s+(已登录|未登录)(?:\\s+\\((.*)\\))?\\s*$");

	/** CLI 输出中的颜色控制字符不属于登录状态。 */
	private static final Pattern ANSI_COLOR = Pattern.compile("\u001B\\[[0-9;]*m");
	/** 批量登录查询的扩展连接超时结论。 */
	private static final String LIST_LOGIN_TIMEOUT = "Wechatsync 登录状态查询超时，请检查 Chrome、扩展和桥接 Token";
	/** 单个平台登录检查的扩展连接超时结论。 */
	private static final String CHECK_LOGIN_TIMEOUT = "Wechatsync 扩展连接或平台登录检查超时，请检查 Chrome、扩展和桥接 Token";

	/**
	 * 所有平台共享桥接端口，同一 JVM 只允许一次 CLI 登录检查或同步。
	 */
	private static final ReentrantLock EXECUTION_LOCK = new ReentrantLock();

	private final WechatsyncProperties properties;

	/**
	 * 批量读取扩展中的平台登录状态；连接超时可唤醒扩展，不触发扫码或内容同步。
	 *
	 * @return 按 Wechatsync 平台标识索引的登录状态
	 */
	public Map<String, WechatsyncLoginAccount> listPlatformLoginStatuses() {
		if (!EXECUTION_LOCK.tryLock()) {
			throw new PublicationClientException("Wechatsync 正在同步，请稍后再查询登录状态");
		}
		try {
			// 1. 与同步共用桥接互斥边界，一次获取扩展的所有平台状态。
			String output = runLoginCommand(this::createListLoginStatusesCommand, LIST_LOGIN_TIMEOUT,
					"Wechatsync 登录状态查询失败，请检查 Chrome、扩展和桥接 Token");
			log.info("listPlatformLoginStatuses 响应成功:{}",output);
			// 2. 只解析明确的登录结论；无法确认的输出交给上层标记为未知。
			return parsePlatformLoginStatuses(output);
		} catch (IOException exception) {
			throw new PublicationClientException("Wechatsync 登录状态查询读写失败，请检查 CLI 和本机环境", exception);
		} finally {
			EXECUTION_LOCK.unlock();
		}
	}


	/** 构造一次查询全部平台登录状态的 CLI 命令。 */
	ProcessBuilder createListLoginStatusesCommand() {
		return buildCommand(properties.getAuthTimeoutSeconds(), "platforms", "--auth");
	}

	/**
	 * 从 CLI 平台列表解析明确的登录状态；列表格式失效时拒绝推测。
	 *
	 * @param output CLI 原始输出
	 * @return 已明确返回的平台状态
	 */
	Map<String, WechatsyncLoginAccount> parsePlatformLoginStatuses(String output) {
		String text = ANSI_COLOR.matcher(output).replaceAll("");
		// CLI 某些失败分支退出码仍为零；必须先确认平台列表标题。
		if (!text.contains("Chrome Extension 已连接") || !text.contains("支持的平台 (")) {
			throw new PublicationClientException("无法确认 Wechatsync 平台登录状态，请检查 Chrome 和扩展");
		}
		Map<String, WechatsyncLoginAccount> statuses = new LinkedHashMap<>();
		for (String line : text.split("\\R")) {
			Matcher matcher = LOGIN_STATUS_LINE.matcher(line);
			if (!matcher.matches()) {
				continue;
			}
			// 只接受符号与中文结论一致的行，避免把未知格式误判为未登录。
			boolean loggedIn = "✓".equals(matcher.group(2)) && "已登录".equals(matcher.group(3));
			boolean loggedOut = "✗".equals(matcher.group(2)) && "未登录".equals(matcher.group(3));
			if (loggedIn || loggedOut) {
				statuses.put(matcher.group(1), new WechatsyncLoginAccount(loggedIn,
						loggedIn ? matcher.group(4) : null));
			}
		}
		if (statuses.isEmpty()) {
			throw new PublicationClientException("Wechatsync 未返回可识别的平台登录状态");
		}
		return statuses;
	}

	/**
	 * 表示 Wechatsync 明确返回的登录结论；未返回的平台不构造此对象。
	 *
	 * @param loggedIn 是否已登录
	 * @param username 登录账号名，可能为空
	 */
	public record WechatsyncLoginAccount(boolean loggedIn, String username) { }

	/**
	 * 将 Markdown 提交给指定平台，返回 CLI 原始输出供平台策略判定。
	 *
	 * @param platform Wechatsync 平台标识，由平台策略指定
	 * @param title    文章标题
	 * @param content  Markdown 正文
	 * @return CLI 输出，不得直接记录日志或返回给外部用户
	 */
	public String sync(String platform, String title, String content) {
		// 1. 各平台共享互斥边界，防止争用浏览器扩展桥接端口。
		if (! EXECUTION_LOCK.tryLock()) {
			throw new PublicationClientException("Wechatsync 正在同步，请稍后再试");
		}
		Path article = null;
		try {
			// 2. 使用与同步相同的桥接凭证检查目标平台登录状态，失败时不提交正文。
			verifyPlatformLogin(platform);
			// 3. 正文通过文件传递，标题与平台使用独立参数，不经过 shell。
			article = Files.createTempFile("geo-wechatsync-", ".md");
			Files.writeString(article, content, StandardCharsets.UTF_8);
			return runCommand(createContentPublishCommand(platform, title, article));
		} catch (IOException exception) {
			throw new PublicationClientException("Wechatsync 文件或进程读写失败，请核对草稿箱及本机环境", exception);
		} finally {
			// 4. 清理正文并释放桥接使用权，不在结果不确定时自动重试。
			deleteArticle(article);
			EXECUTION_LOCK.unlock();
		}
	}

	/**
	 * 构造内容投放命令
	 */
	ProcessBuilder createContentPublishCommand(String platform, String title, Path article) {
		return buildCommand(properties.getTimeoutSeconds(), "sync", article.toString(),
				"-p", platform, "--title=" + title);
	}

	/**
	 * 构造平台登录状态检查命令；auth 不执行扫码登录。
	 */
	ProcessBuilder createCheckLoginCommand(String platform) {
		return buildCommand(properties.getAuthTimeoutSeconds(), "auth", platform);
	}

	/**
	 * 构造 CLI 命令并配置桥接凭证，供内容投放和登录检查共用。
	 */
	private ProcessBuilder buildCommand(int operationTimeoutSeconds, String... arguments) {
		// 校验 CLI 执行配置，避免将无效路径或超时传入进程。
		if (properties.getExecutable() == null || properties.getExecutable().isBlank()
				|| operationTimeoutSeconds <= 0 || properties.getConnectionTimeoutMillis() <= 0) {
			throw new PublicationClientException("Wechatsync CLI 路径及超时配置无效");
		}
		List<String> command = new ArrayList<>(List.of(properties.getExecutable(),
				"--timeout", String.valueOf(properties.getConnectionTimeoutMillis())));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command);
		// 显式 Token 优先；留空时兼容已有环境变量配置，不输出凭证。
		String token = properties.getToken();
		if (token == null || token.isBlank()) {
			token = builder.environment().get("WECHATSYNC_TOKEN");
		}
		if (token == null || token.isBlank()) {
			throw new PublicationClientException("请配置 Wechatsync token 或 WECHATSYNC_TOKEN 环境变量");
		}
		builder.environment().put("WECHATSYNC_TOKEN", token);
		builder.environment().put("NO_COLOR", "1");
		builder.environment().put("FORCE_COLOR", "0");
		log.info("执行 Wechatsync，command={}，connectionTimeoutMillis={}，timeoutSeconds={}（Token 通过环境变量传递）",
				formatCommand(builder), properties.getConnectionTimeoutMillis(), operationTimeoutSeconds);
		return builder.redirectErrorStream(true);
	}

	/**
	 * 登录状态检查只作为提交前置条件；超时可唤醒扩展并重查，不记录可能含账号名的 CLI 输出。
	 */
	private void verifyPlatformLogin(String platform) {
		log.info("检查 Wechatsync 平台登录状态，platform={}", platform);
		String output;
		// 提交正文前先调用 CLI 检查登录；读写失败与 CLI 超时保留不同的异常语义。
		try {
			output = runLoginCommand(() -> createCheckLoginCommand(platform), CHECK_LOGIN_TIMEOUT,
					"Wechatsync 登录检查失败，请检查 Chrome、扩展和桥接 Token");
		} catch (IOException ex) {
			throw new PublicationClientException("Wechatsync 登录检查读写失败，请检查 CLI 和本机环境", ex);
		}
		// CLI 的 auth 命令不执行登录；必须确认扩展连接和目标平台已登录两项结果。
		if (! output.contains("Chrome Extension 已连接")) {
			throw new PublicationClientException("Wechatsync 扩展未连接，请检查 Chrome、扩展和桥接 Token");
		}
		if (output.lines().map(String::trim).noneMatch(line -> line.equals("✓ " + platform + " 已登录"))) {
			throw new PublicationClientException(output.contains(platform + " 未登录")
					? "目标平台 " + platform + " 未登录，请先在 Chrome 中登录"
					: "无法确认目标平台 " + platform + " 的登录状态，请检查浏览器扩展");
		}
	}

	/**
	 * 登录检查超时时唤醒 Chrome 扩展并重查一次；只读命令可重查，正文同步不得自动重试。
	 *
	 * @param createCommand 每次创建独立的登录查询命令
	 * @param timeoutMessage 登录查询的超时提示
	 * @param exitMessage CLI 异常退出的提示
	 * @return CLI 登录查询结果
	 */
	private String runLoginCommand(Supplier<ProcessBuilder> createCommand, String timeoutMessage,
	                               String exitMessage) throws IOException {
		try {
			return runCommand(createCommand.get(), properties.getAuthTimeoutSeconds(), timeoutMessage, exitMessage);
		} catch (PublicationClientException exception) {
			// 只有扩展连接超时才尝试唤醒；平台未登录或其他错误不应掩盖原始结论。
			if (!timeoutMessage.equals(exception.getMessage()) || !canWakeChromeExtension()) {
				throw exception;
			}
			log.warn("Wechatsync 登录查询超时，唤醒 Chrome 扩展后重查一次");
			wakeChromeExtension();
			return runCommand(createCommand.get(), properties.getAuthTimeoutSeconds(), timeoutMessage, exitMessage);
		}
	}

	/** 仅在 macOS 且已配置扩展页面时启用本机唤醒。 */
	private boolean canWakeChromeExtension() {
		return System.getProperty("os.name", "").startsWith("Mac")
				&& properties.getExtensionPopupUrl() != null && !properties.getExtensionPopupUrl().isBlank();
	}

	/** 扩展页面会唤醒休眠的 service worker，使其重新连接 CLI 的 9527 端口。 */
	private void wakeChromeExtension() {
		Process process;
		try {
			process = createWakeChromeExtensionCommand().start();
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new PublicationClientException("唤醒 Chrome 扩展超时，请检查浏览器是否可用");
			}
			if (process.exitValue() != 0) {
				throw new PublicationClientException("无法打开文章同步助手扩展页面，请检查 Chrome 安装状态");
			}
		} catch (IOException exception) {
			throw new PublicationClientException("无法唤醒 Chrome 扩展，请检查本机 open 命令", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new PublicationClientException("唤醒 Chrome 扩展被中断", exception);
		}
	}

	/** 构造 macOS 后台打开扩展页面的命令，供登录超时后唤醒浏览器。 */
	ProcessBuilder createWakeChromeExtensionCommand() {
		return new ProcessBuilder("/usr/bin/open", "-g", "-a", "Google Chrome", properties.getExtensionPopupUrl())
				.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
	}

	/**
	 * 按本机 POSIX shell 规则引用各参数，便于复制命令；不包含环境变量中的凭证。
	 */
	String formatCommand(ProcessBuilder builder) {
		return builder.command().stream().map(argument -> "'" + argument.replace("'", "'\"'\"'") + "'")
				.collect(java.util.stream.Collectors.joining(" "));
	}

	/**
	 * 执行 CLI 并限时等待；关闭标准输入，防止 CLI 交互提示阻塞服务。
	 */
	String runCommand(ProcessBuilder builder) throws IOException {
		return runCommand(builder, properties.getTimeoutSeconds(),
				"Wechatsync 同步超时，草稿可能已创建，请先核对草稿箱再重试",
				"Wechatsync CLI 异常退出，请核对草稿箱及本机扩展状态", true);
	}

	/**
	 * 对登录检查和正文同步分别设总超时，避免扩展失联时一直等待正文同步超时。
	 */
	private String runCommand(ProcessBuilder builder, int timeoutSeconds, String timeoutMessage,
	                          String exitMessage) throws IOException {
		return runCommand(builder, timeoutSeconds, timeoutMessage, exitMessage, false);
	}

	/**
	 * 正文命令启动后的异常一律保留待核对语义；登录检查失败仍是未提交正文的确定失败。
	 */
	private String runCommand(ProcessBuilder builder, int timeoutSeconds, String timeoutMessage,
	                          String exitMessage, boolean contentCommand) throws IOException {
		// 1. 启动本地 CLI，路径或运行环境缺失时给出可操作的提示。
		Process process;
		try {
			process = builder.start();
		} catch (IOException exception) {
			throw new PublicationClientException("无法启动 Wechatsync CLI，请安装 1.1.0 并检查 executable 路径及 Node 的 PATH", exception);
		}
		FutureTask<String> output = new FutureTask<>(() -> readOutput(process.getInputStream()));
		Thread reader = new Thread(output, "wechatsync-cli-output");
		reader.setDaemon(true);
		reader.start();
		try {
			// 2. 等待执行与收集输出分别设限；持续消费输出，防止管道写满造成死锁。
			process.getOutputStream().close();
			if (! process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				throw commandFailure(timeoutMessage, null, contentCommand);
			}
			String text = output.get(2, TimeUnit.SECONDS);
			if (process.exitValue() != 0) {
				throw commandFailure(exitMessage, null, contentCommand);
			}
			return text;
		} catch (IOException exception) {
			throw commandFailure("Wechatsync CLI 读写失败，请核对草稿箱及本机环境", exception, contentCommand);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw commandFailure("Wechatsync CLI 被中断；如已提交正文，请先核对草稿箱", exception, contentCommand);
		} catch (ExecutionException | TimeoutException exception) {
			throw commandFailure("无法确认 Wechatsync CLI 执行结果；如已提交正文，请先核对草稿箱", exception, contentCommand);
		} finally {
			// 3. 包装脚本可能产生子进程；结束本次进程树，避免继续占用桥接端口。
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			output.cancel(true);
			try {
				process.getInputStream().close();
			} catch (IOException exception) {
				log.warn("Wechatsync 输出流关闭失败");
			}
		}
	}

	/**
	 * 用命令阶段区分确定失败与可能已提交正文，避免把后者开放为可重试记录。
	 */
	private PublicationClientException commandFailure(String message, Throwable cause, boolean contentCommand) {
		return contentCommand ? new PublicationOutcomeUnknownException(message, cause)
				: new PublicationClientException(message, cause);
	}

	/**
	 * 最多保留 64 KiB CLI 输出；超量时拒绝解析，避免异常进程耗尽服务内存。
	 */
	private String readOutput(InputStream stream) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int count;
		while ((count = stream.read(buffer)) != - 1) {
			if (output.size() + count > 65536) {
				throw new IOException("CLI 输出超过限制");
			}
			output.write(buffer, 0, count);
		}
		return output.toString(StandardCharsets.UTF_8);
	}

	/**
	 * 删除本次正文临时文件；清理失败只记录提示，不覆盖已经取得的投放结果。
	 */
	private void deleteArticle(Path article) {
		if (article == null) {
			return;
		}
		try {
			Files.deleteIfExists(article);
		} catch (IOException exception) {
			log.warn("Wechatsync 临时文件清理失败，path={}", article);
		}
	}
}
