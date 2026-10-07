package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;

import java.time.LocalDateTime;

/**
 * platform_browser_login 表映射；每个平台一条浏览器会话，不存账号密码。
 */
@Data
@TableName("platform_browser_login")
public class PlatformBrowserLoginEntity {

    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 平台枚举名称。 */
    private String platformType;
    /** 平台明确返回的账号名。 */
    private String accountName;
    /** 最近一次账号登录结论，存储 {@link PlatformBrowserLoginStatus} 的名称；登录任务进度不入库。 */
    private String loginStatus;
    /** Playwright storageState JSON；仅供服务端恢复浏览器会话。 */
    private String storageState;
    /** 最近一次扫码登录时间。 */
    private LocalDateTime lastLoginAt;
    /** 最近一次主动核验时间。 */
    private LocalDateTime lastCheckedAt;
    /** 最近一次更新的时间。 */
    private LocalDateTime updatedAt;
}
