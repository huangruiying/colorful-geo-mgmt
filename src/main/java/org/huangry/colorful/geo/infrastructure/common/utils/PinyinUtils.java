package org.huangry.colorful.geo.infrastructure.common.utils;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

/**
 * 把中文文本转成用于搜索的拼音：全拼和首字母，均为小写、不带声调、不含空格和标点。
 * <p>汉字读音来自 pinyin4j 自带的对照表；多音字只取第一个读音（如“重庆”得到 zhongqing）。
 * 英文字母和数字原样转小写保留，其他字符丢弃。不负责匹配逻辑，匹配由调用方完成。</p>
 */
public final class PinyinUtils {

    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinUtils() {
    }

    /**
     * 转成全拼，如“预发布测试”得到 yufabuceshi。
     *
     * @param text 原文，可为空
     * @return 全拼；原文为空时返回空字符串
     */
    public static String toFullPinyin(String text) {
        return convert(text, false);
    }

    /**
     * 转成拼音首字母，如“预发布测试”得到 yfbcs。
     *
     * @param text 原文，可为空
     * @return 首字母；原文为空时返回空字符串
     */
    public static String toInitials(String text) {
        return convert(text, true);
    }

    /**
     * 逐字转换：汉字取第一个读音（全拼或首字母），字母和数字转小写保留，其他字符丢弃。
     *
     * @param text 原文，可为空
     * @param initialsOnly 是否只取首字母
     * @return 转换结果
     */
    private static String convert(String text, boolean initialsOnly) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        for (char character : text.toCharArray()) {
            String[] readings = readings(character);
            if (readings != null && readings.length > 0) {
                result.append(initialsOnly ? readings[0].substring(0, 1) : readings[0]);
            } else if (Character.isLetterOrDigit(character) && character < 128) {
                result.append(Character.toLowerCase(character));
            }
        }
        return result.toString();
    }

    /**
     * 查询单个字符的拼音读音。
     *
     * @param character 字符
     * @return 全部读音；不是汉字时为 null
     */
    private static String[] readings(char character) {
        try {
            return PinyinHelper.toHanyuPinyinStringArray(character, FORMAT);
        } catch (BadHanyuPinyinOutputFormatCombination exception) {
            // 输出格式在类加载时固定且组合合法，出现即为代码错误
            throw new IllegalStateException("拼音输出格式配置错误", exception);
        }
    }
}
