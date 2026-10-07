package org.huangry.colorful.geo.infrastructure.client.publish.browser;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 浏览器发布共用的动作超时配置；发布登录态从数据库读取。
 * 配置前缀从知乎专用改为通用 publish.browser，供后续其他平台的浏览器发布复用。
 *
 * @author huangry
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "colorful.geo.publish.browser")
public class PublicationBrowserProperties {

    /** 单个页面动作等待上限，单位毫秒。 */
    private int timeoutMillis = 30000;
}
