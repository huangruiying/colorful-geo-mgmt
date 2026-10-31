package org.huangry.colorful.geo.infrastructure.client.playwright.takeover;

/**
 * 接管窗口的一帧画面：服务器页面截下的 JPEG 及其对应的页面尺寸。
 *
 * @param imageBase64 JPEG 图片的 Base64 内容，不写入日志
 * @param width 页面宽度（CSS 像素），前端据此把点击位置换算成页面坐标
 * @param height 页面高度（CSS 像素）
 * @param sequence 帧序号，递增；前端据此跳过已画过的帧
 */
public record BrowserTakeoverFrame(String imageBase64, int width, int height, long sequence) { }
