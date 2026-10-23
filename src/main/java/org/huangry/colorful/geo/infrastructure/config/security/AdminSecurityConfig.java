package org.huangry.colorful.geo.infrastructure.config.security;

import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 管理后台的登录与访问控制：只有配置中的唯一账号能登录，不提供注册。
 * <p>除登录页及其样式脚本、robots.txt 外，所有页面和接口都要求登录；未登录访问页面跳转登录页，访问接口返回 401。
 * 写操作必须携带跨站令牌；所有响应声明不被搜索引擎与爬虫收录。同一 IP 连续登录失败由 {@link LoginAttemptLimiter} 锁定。</p>
 */
@Slf4j
@Configuration
public class AdminSecurityConfig {
    /** 登录页地址；未登录访问页面、退出登录后都回到这里。 */
    static final String LOGIN_PAGE = "/admin/login.html";
    /** 登录表单提交地址，由 Spring Security 校验账号密码。 */
    static final String LOGIN_PROCESSING_URL = "/login";
    /** 登录成功且没有原访问页面时进入的首页，与导航中的品牌链接一致；根路径与 /admin 目录也跳转到这里。 */
    static final String HOME_PAGE = "/admin/contents.html";
    /** 未登录访问接口的响应正文，与全局异常一致使用 ProblemDetail 结构，前端读取 detail。 */
    private static final String UNAUTHENTICATED_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"未登录或登录已过期，请重新登录\"}";
    /** 跨站令牌缺失或失效的响应正文；唯一账号拥有全部权限，403 只会来自令牌校验。 */
    private static final String FORBIDDEN_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"页面已过期，请刷新页面后重试\"}";

    /** 唯一登录账号的用户名。 */
    @Value("${admin.login.username}")
    private String username;
    /** 唯一登录账号密码的 BCrypt 哈希，不保存明文。 */
    @Value("${admin.login.password-hash}")
    private String passwordHash;
    @Resource
    private LoginAttemptLimiter loginAttemptLimiter;

    /**
     * 密码统一用 BCrypt 校验，配置中只出现哈希。
     * @return BCrypt 密码校验器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 注册唯一登录账号；没有用户表，也没有注册入口。
     * @return 只包含配置账号的用户来源
     * @throws IllegalStateException 未配置密码哈希或不是 BCrypt 格式，服务拒绝启动，避免以空密码或明文上线
     */
    @Bean
    public UserDetailsService userDetailsService() {
        if (passwordHash == null || !passwordHash.startsWith("$2")) {
            throw new IllegalStateException("未配置管理后台登录密码：请设置环境变量 GEO_ADMIN_PASSWORD_HASH 为 BCrypt 哈希");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username).password(passwordHash).roles("ADMIN").build());
    }

    /**
     * 组装访问控制、登录、退出、跨站令牌与响应头。
     * @param http Spring Security 配置入口
     * @return 生效的过滤链
     * @throws Exception Spring Security 组装失败
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 浏览器打开页面时 Accept 含 text/html，跳转登录页；接口请求由前端发出 JSON，返回 401 由前端统一跳转
        RequestMatcher pageRequest = request -> {
            String accept = request.getHeader("Accept");
            return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
        };
        // 只记住页面地址，登录后回到原页面；接口地址不记，避免登录后跳到一段 JSON
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(pageRequest);
        SavedRequestAwareAuthenticationSuccessHandler successHandler = new SavedRequestAwareAuthenticationSuccessHandler();
        successHandler.setDefaultTargetUrl(HOME_PAGE);
        successHandler.setRequestCache(requestCache);

        // 1. 访问范围：登录页自身用到的资源与 robots.txt 公开，其余全部要求登录
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(LOGIN_PAGE, "/admin/login.js", "/admin/login.css", "/admin/styles.css", "/admin/shared/http.js", "/robots.txt", "/error")
                .permitAll()
                .anyRequest().authenticated());
        // 2. 表单登录与退出：成功清除失败计数，失败计数并提示，退出回到登录页
        http.requestCache(cache -> cache.requestCache(requestCache))
                .formLogin(form -> form.loginPage(LOGIN_PAGE).loginProcessingUrl(LOGIN_PROCESSING_URL)
                        .successHandler((request, response, authentication) -> {
                            onLoginSucceeded(request, authentication);
                            successHandler.onAuthenticationSuccess(request, response, authentication);
                        })
                        .failureHandler(this::onLoginFailed))
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl(LOGIN_PAGE + "?logout"));
        http.addFilterBefore(new LoginLockFilter(), UsernamePasswordAuthenticationFilter.class);
        // 3. 未登录与令牌失效：页面跳转登录页，接口返回 ProblemDetail
        http.exceptionHandling(exception -> exception
                .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE), pageRequest)
                .defaultAuthenticationEntryPointFor((request, response, error) ->
                        writeProblem(response, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_BODY), AnyRequestMatcher.INSTANCE)
                .accessDeniedHandler((request, response, error) -> writeProblem(response, HttpStatus.FORBIDDEN, FORBIDDEN_BODY)));
        // 4. 跨站令牌放在前端可读的 Cookie 中，前端写请求放入请求头，登录表单放入隐藏字段
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);
        // 5. 声明所有响应不被搜索引擎和爬虫收录；真正的访问控制仍靠登录
        http.headers(headers -> headers.addHeaderWriter(new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow")));
        return http.build();
    }

    /**
     * 登录成功：清除该 IP 的失败计数并记录登录。
     * @param request 登录请求，取客户端 IP
     * @param authentication 已通过校验的登录信息
     */
    private void onLoginSucceeded(HttpServletRequest request, Authentication authentication) {
        loginAttemptLimiter.recordSuccess(request.getRemoteAddr());
        log.info("后台登录成功 username={} clientIp={}", authentication.getName(), request.getRemoteAddr());
    }

    /**
     * 登录失败：累计失败次数，达到上限后提示已锁定，否则提示账号或密码错误；不区分是账号还是密码错误。
     * @param request 登录请求，取客户端 IP
     * @param response 跳回登录页
     * @param exception 校验失败原因，只用于日志类型
     * @throws IOException 跳转失败
     */
    private void onLoginFailed(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        String clientIp = request.getRemoteAddr();
        loginAttemptLimiter.recordFailure(clientIp);
        log.warn("后台登录失败 clientIp={} reason={}", clientIp, exception.getClass().getSimpleName());
        response.sendRedirect(request.getContextPath() + LOGIN_PAGE + (loginAttemptLimiter.isLocked(clientIp) ? "?locked" : "?error"));
    }

    /**
     * 以 ProblemDetail 结构写出错误响应，供前端统一读取 detail。
     * @param response 当前响应
     * @param status HTTP 状态
     * @param body 固定的 JSON 正文，不包含请求或用户数据
     * @throws IOException 写出失败
     */
    private static void writeProblem(HttpServletResponse response, HttpStatus status, String body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }

    /**
     * 锁定期内的登录提交直接拒绝，不校验密码，避免锁定期间继续猜测。
     */
    private final class LoginLockFilter extends OncePerRequestFilter {
        /** 与表单登录处理相同的匹配规则，只识别登录提交。 */
        private final RequestMatcher loginSubmit = AntPathRequestMatcher.antMatcher(HttpMethod.POST, LOGIN_PROCESSING_URL);

        /**
         * 只拦截登录提交；锁定中跳回登录页并提示，其余请求原样放行。
         * @param request 当前请求
         * @param response 当前响应
         * @param chain 后续过滤链
         * @throws ServletException 后续过滤链失败
         * @throws IOException 跳转或后续过滤链失败
         */
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (loginSubmit.matches(request) && loginAttemptLimiter.isLocked(request.getRemoteAddr())) {
                response.sendRedirect(request.getContextPath() + LOGIN_PAGE + "?locked");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Spring Security 默认延迟生成跨站令牌，只有读取时才写入 Cookie；这里每次请求都读取一次，
     * 保证登录页和普通页面加载后，前端都能从 Cookie 中拿到令牌。
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        /**
         * 读取当前请求的跨站令牌以触发写入 Cookie，然后放行。
         * @param request 当前请求
         * @param response 当前响应
         * @param chain 后续过滤链
         * @throws ServletException 后续过滤链失败
         * @throws IOException 后续过滤链失败
         */
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
