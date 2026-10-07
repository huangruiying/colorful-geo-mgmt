package org.huangry.colorful.geo.infrastructure.common.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 验证投放平台的展示名称与稳定枚举值分离。
 *
 * @author huangry
 */
class PublicationPlatformTypeTest {

    /** 每个平台都显式维护可展示名称，不再依赖枚举名兜底。 */
    @ParameterizedTest
    @EnumSource(PublicationPlatformType.class)
    void 每个平台都有独立展示名称(PublicationPlatformType platformType) {
        assertFalse(platformType.getDisplayName().isBlank());
        assertNotEquals(platformType.name(), platformType.getDisplayName());
    }

    /** 平台展示名称不改变路由使用的枚举名称。 */
    @Test
    void 展示名称不改变平台代码() {
        assertEquals("知乎", PublicationPlatformType.ZHIHU.getDisplayName());
        assertEquals("掘金", PublicationPlatformType.JUEJIN.getDisplayName());
        assertEquals("ZHIHU", PublicationPlatformType.ZHIHU.name());
    }
}
