package org.huangry.colorful.geo.infrastructure.config.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 后台入口地址跳转：直接访问站点根路径或 /admin 目录时进入后台首页。
 * <p>这些地址没有对应页面；未登录访问时会被记为登录后的回跳地址，不跳转就会在登录成功后显示 404。
 * 访问控制仍由 {@link AdminSecurityConfig} 负责，本类不放行任何地址。</p>
 */
@Configuration
public class AdminHomePageConfig implements WebMvcConfigurer {

    /**
     * 注册根路径与 /admin 目录到后台首页的重定向。
     *
     * @param registry Spring MVC 视图控制器注册表
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", AdminSecurityConfig.HOME_PAGE);
        registry.addRedirectViewController("/admin", AdminSecurityConfig.HOME_PAGE);
        registry.addRedirectViewController("/admin/", AdminSecurityConfig.HOME_PAGE);
    }
}
