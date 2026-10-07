package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;

/**
 * 独立浏览器登录态 DAO；按平台定位首版唯一账号，不参与发布记录查询。
 */
@Mapper
public interface PlatformBrowserLoginDao extends BaseMapper<PlatformBrowserLoginEntity> {

    /**
     * 查询平台唯一登录态。
     *
     * @param platformType 平台枚举名称
     * @return 登录态；未登录时为空
     */
    @Select("SELECT * FROM platform_browser_login WHERE platform_type = #{platformType}")
    PlatformBrowserLoginEntity findByPlatform(@Param("platformType") String platformType);

    /**
     * 保存本次明确成功的登录态；唯一键确保首版每个平台只有一个账号。
     *
     * @param platformType 平台枚举名称
     * @param accountName 平台返回的账号名
     * @param storageState Playwright 浏览器会话 JSON
     * @return 受影响行数
     */
    @Insert("""
            INSERT INTO platform_browser_login
              (platform_type, account_name, login_status, storage_state, last_login_at, last_checked_at)
            VALUES (#{platformType}, #{accountName}, 'LOGGED_IN', #{storageState}, NOW(3), NOW(3))
            ON DUPLICATE KEY UPDATE account_name = VALUES(account_name), login_status = 'LOGGED_IN',
              storage_state = VALUES(storage_state), last_login_at = NOW(3), last_checked_at = NOW(3)
            """)
    int saveSuccessfulLogin(@Param("platformType") String platformType,
                            @Param("accountName") String accountName,
                            @Param("storageState") String storageState);

    /**
     * 保存主动核验结论；未知状态保留已存登录态，允许稍后重试。
     *
     * @param platformType 平台枚举名称
     * @param status 明确过期、已登录或未知
     * @param accountName 平台返回的账号名
     * @return 受影响行数
     */
    @Update("""
            UPDATE platform_browser_login SET login_status = #{status},
              account_name = COALESCE(#{accountName}, account_name), last_checked_at = NOW(3)
            WHERE platform_type = #{platformType}
            """)
    int updateCheckedStatus(@Param("platformType") String platformType,
                            @Param("status") String status,
                            @Param("accountName") String accountName);
}
