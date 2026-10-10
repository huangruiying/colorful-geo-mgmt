package org.huangry.colorful.geo.regtest;

import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 脱离 Spring，直接从数据库读取平台浏览器登录态，并用动态代理桩把真实 storage_state
 * 注入到真实客户端。这样可在不启动整个应用的情况下，驱动真实的 *BrowserClient 跑端到端回归。
 *
 * <p>凭据解析优先级（与项目约定一致）：环境变量 &gt; .test/regtest/.env &gt; 空。
 * 密码默认不写死在代码里，由运行环境提供。</p>
 */
public final class PlatformLoginState {

    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/colorful_geo?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";

    static {
        try {
            // mysql-connector-j 8 走 SPI 自动注册；此处显式触发一次，避免某些类加载顺序下漏注册。
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Throwable ignored) {
            // 驱动缺失会在 getConnection 时以明确异常抛出，这里不阻塞类加载。
        }
    }

    private PlatformLoginState() {
    }

    /** 读取环境变量或默认值。 */
    static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /**
     * 从 platform_browser_login 读取某平台的真实登录态（含 storage_state）。
     *
     * @param platformType 平台枚举名，如 EASTMONEY / JIANSHU
     * @return 登录态实体；无记录时返回 null
     */
    public static PlatformBrowserLoginEntity load(String platformType) throws Exception {
        String url = env("GEO_DB_URL", DEFAULT_URL);
        String user = env("GEO_DB_USER", "geo_app");
        String pass = env("GEO_DB_PASS", "");
        if (pass.isBlank()) {
            throw new IllegalStateException(
                    "缺少数据库密码：请设置环境变量 GEO_DB_PASS，或在 .test/regtest/.env 中配置 GEO_DB_PASS");
        }
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT platform_type, account_name, login_status, storage_state "
                             + "FROM platform_browser_login WHERE platform_type = ?")) {
            ps.setString(1, platformType);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                PlatformBrowserLoginEntity e = new PlatformBrowserLoginEntity();
                e.setPlatformType(rs.getString("platform_type"));
                e.setAccountName(rs.getString("account_name"));
                e.setLoginStatus(rs.getString("login_status"));
                e.setStorageState(rs.getString("storage_state"));
                return e;
            }
        }
    }

    /**
     * 用一个只实现 findByPlatform 的动态代理桩替换真实 DAO，返回 load 出来的实体。
     * 客户端只调用 findByPlatform，其余接口方法（BaseMapper 的一堆）一律抛 UnsupportedOperationException，
     * 不会在回归路径上被触发。
     */
    public static PlatformBrowserLoginDao stubDao(String platformType, PlatformBrowserLoginEntity entity) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("findByPlatform".equals(name)) {
                return entity;
            }
            if ("toString".equals(name)) {
                return "StubPlatformBrowserLoginDao(" + platformType + ")";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            throw new UnsupportedOperationException("回归桩仅支持 findByPlatform，收到调用: " + name);
        };
        return (PlatformBrowserLoginDao) Proxy.newProxyInstance(
                PlatformBrowserLoginDao.class.getClassLoader(),
                new Class<?>[]{PlatformBrowserLoginDao.class},
                handler);
    }
}
