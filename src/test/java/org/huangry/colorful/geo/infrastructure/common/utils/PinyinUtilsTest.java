package org.huangry.colorful.geo.infrastructure.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证搜索用拼音：小写无声调，保留字母数字，去掉空格和标点。 */
class PinyinUtilsTest {

    /** 汉字转全拼和首字母，英文数字转小写保留，空格和标点丢弃。 */
    @Test
    void 汉字转全拼和首字母() {
        assertEquals("yufabuceshigeo2026", PinyinUtils.toFullPinyin("预发布测试：GEO 2026！"));
        assertEquals("yfbcsgeo2026", PinyinUtils.toInitials("预发布测试：GEO 2026！"));
    }

    /** ü 用 v 表示，便于键盘输入。 */
    @Test
    void 韵母ü用v表示() {
        assertEquals("lvxing", PinyinUtils.toFullPinyin("旅行"));
    }

    /** 空标题返回空字符串，不抛异常。 */
    @Test
    void 空标题返回空字符串() {
        assertEquals("", PinyinUtils.toFullPinyin(null));
        assertEquals("", PinyinUtils.toInitials(""));
    }
}
