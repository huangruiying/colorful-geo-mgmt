package org.huangry.colorful.geo.infrastructure.config.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在真实的 Spring Security 过滤链上验证后台登录：未登录处理、登录与锁定、跨站令牌、禁止收录响应头。
 * 只加载安全配置与一个探针接口，不连接数据库。
 */
@SpringJUnitWebConfig(AdminSecurityConfigTest.TestConfig.class)
class AdminSecurityConfigTest {
    private static final String PASSWORD = "correct-password";

    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @DynamicPropertySource
    static void adminAccount(DynamicPropertyRegistry registry) {
        registry.add("admin.login.username", () -> "admin");
        registry.add("admin.login.password-hash", () -> new BCryptPasswordEncoder().encode(PASSWORD));
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void shouldRedirectPageRequestToLoginWhenNotLoggedIn() throws Exception {
        mvc.perform(get("/probe").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/admin/login.html"));
    }

    @Test
    void shouldRejectApiRequestWith401ProblemWhenNotLoggedIn() throws Exception {
        mvc.perform(get("/probe").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("未登录或登录已过期，请重新登录"));
    }

    @Test
    void shouldLoginWithConfiguredAccountAndThenAccessApi() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin("/login").user("admin").password(PASSWORD))
                .andExpect(redirectedUrl("/admin/contents.html"))
                .andReturn().getRequest().getSession();

        mvc.perform(get("/probe").session(session).accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
    }

    /** 未登录打开根路径或 /admin 目录：登录后回到原地址，再由入口跳转进入首页，不出现 404。 */
    @Test
    void shouldReachHomePageAfterLoginFromSiteEntryAddress() throws Exception {
        for (String entryUrl : new String[]{"/", "/admin", "/admin/"}) {
            MockHttpSession session = new MockHttpSession();
            mvc.perform(get(entryUrl).session(session).accept(MediaType.TEXT_HTML))
                    .andExpect(redirectedUrl("http://localhost/admin/login.html"));
            mvc.perform(post("/login").session(session).param("username", "admin").param("password", PASSWORD).with(csrf()))
                    .andExpect(redirectedUrl("http://localhost" + entryUrl + "?continue"));

            mvc.perform(get(entryUrl).param("continue", "").session(session).accept(MediaType.TEXT_HTML))
                    .andExpect(redirectedUrl("/admin/contents.html"));
        }
    }

    /** 锁定按客户端 IP 计数；本用例使用独立 IP，失败次数不影响其他用例的登录。 */
    @Test
    void shouldLockAfterFiveFailuresEvenWithCorrectPassword() throws Exception {
        for (int attempt = 1; attempt < LoginAttemptLimiter.MAX_FAILURES; attempt++) {
            mvc.perform(loginFrom("203.0.113.9", "wrong")).andExpect(redirectedUrl("/admin/login.html?error"));
        }
        mvc.perform(loginFrom("203.0.113.9", "wrong")).andExpect(redirectedUrl("/admin/login.html?locked"));

        mvc.perform(loginFrom("203.0.113.9", PASSWORD)).andExpect(redirectedUrl("/admin/login.html?locked"));
    }

    @Test
    void shouldRequireCsrfTokenForWriteRequests() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin("/login").user("admin").password(PASSWORD))
                .andReturn().getRequest().getSession();

        mvc.perform(post("/probe").session(session).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("页面已过期，请刷新页面后重试"));
        mvc.perform(post("/probe").session(session).with(csrf().asHeader()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void shouldMarkEveryResponseAsNoIndexAndKeepRobotsTxtPublic() throws Exception {
        mvc.perform(get("/probe").accept(MediaType.APPLICATION_JSON)).andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
        // 测试上下文没有静态资源，robots.txt 返回 404 而不是 401，说明它不要求登录
        mvc.perform(get("/robots.txt")).andExpect(status().isNotFound());
    }

    @Test
    void shouldRefuseToStartWithoutBcryptPasswordHash() {
        AdminSecurityConfig config = new AdminSecurityConfig();
        ReflectionTestUtils.setField(config, "username", "admin");
        ReflectionTestUtils.setField(config, "passwordHash", "");

        assertThrows(IllegalStateException.class, config::userDetailsService);
    }

    /** 以指定客户端 IP 提交登录表单，附带跨站令牌。 */
    private static MockHttpServletRequestBuilder loginFrom(String clientIp, String password) {
        return post("/login").param("username", "admin").param("password", password).with(csrf())
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                });
    }

    /** 只装配安全配置、入口跳转、限流器与探针接口。 */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({AdminSecurityConfig.class, AdminHomePageConfig.class, LoginAttemptLimiter.class, ProbeController.class})
    static class TestConfig {
    }

    /** 代表任意需要登录的业务接口。 */
    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        String read() {
            return "{}";
        }

        @PostMapping("/probe")
        String write() {
            return "{}";
        }
    }
}
