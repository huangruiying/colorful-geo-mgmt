package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeover;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverFrame;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverInput;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 浏览器人工接管窗口的接口：管理页发现待接管、取服务器页面画面、转发用户操作和放弃接管。
 * <p>记录只在内存登记表中；已结束或不存在的接管返回 404，窗口据此关闭。画面与操作不写日志。</p>
 */
@RestController
@RequestMapping("/browser-takeovers")
public class MgmtBrowserTakeoverController {

    @Resource
    private BrowserTakeoverRegistry registry;

    /**
     * 等待中的人工接管列表，按登记先后排序。
     *
     * @return 待接管记录，没有时为空列表
     */
    @GetMapping
    public List<BrowserTakeoverResponse> listTakeovers() {
        return registry.listTakeovers().stream().map(MgmtBrowserTakeoverController::toResponse).toList();
    }

    /**
     * 取服务器页面的最新一帧画面；首次调用后服务器才开始截图。
     *
     * @param takeoverId 接管标识
     * @return 最新画面；第一帧还没到时返回 204
     */
    @GetMapping("/{takeoverId}/frame")
    public ResponseEntity<BrowserTakeoverFrame> getTakeoverFrame(@PathVariable("takeoverId") String takeoverId) {
        return registry.getTakeover(takeoverId).readLatestFrame()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 转发一次鼠标、滚轮或键盘操作到服务器页面，按提交顺序执行。
     *
     * @param takeoverId 接管标识
     * @param input 用户操作；不合法返回 400
     */
    @PostMapping("/{takeoverId}/input")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addTakeoverInput(@PathVariable("takeoverId") String takeoverId,
                                 @RequestBody BrowserTakeoverInput input) {
        registry.getTakeover(takeoverId).addInput(input);
    }

    /**
     * 放弃接管；原业务随即结束等待并按失败处理。
     *
     * @param takeoverId 接管标识
     */
    @PostMapping("/{takeoverId}/abandon")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abandonTakeover(@PathVariable("takeoverId") String takeoverId) {
        registry.getTakeover(takeoverId).abandon();
    }

    /**
     * 转成页面展示用的接管信息，不含画面和操作。
     *
     * @param takeover 接管记录
     * @return 接口响应
     */
    private static BrowserTakeoverResponse toResponse(BrowserTakeover takeover) {
        return new BrowserTakeoverResponse(takeover.getTakeoverId(), takeover.getTitle(), takeover.getReason(),
                takeover.getStartedAt(), takeover.getExpiresAt());
    }

    /**
     * 一条等待中的人工接管。
     *
     * @param takeoverId 接管标识（随机串，非数据库 ID）
     * @param title 接管对象，如“搜狐号 · 预发布”
     * @param reason 需要人工的原因
     * @param startedAt 登记时间
     * @param expiresAt 等待期限，到期后原业务按超时失败
     */
    public record BrowserTakeoverResponse(String takeoverId, String title, String reason,
                                          Instant startedAt, Instant expiresAt) { }
}
