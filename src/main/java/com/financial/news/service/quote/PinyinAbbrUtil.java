package com.financial.news.service.quote;

import com.github.promeg.pinyinhelper.Pinyin;

/**
 * 名称拼音首字母工具（TinyPinyin）：非中文字符保留原字符（字母数字大写），其余丢弃。
 * 例：贵州茅台→GZMT；阿里巴巴-W→ALBBW；沪深300→HS300。
 *
 * @author financial-news
 * @since 1.0.0
 */
public final class PinyinAbbrUtil {

    private PinyinAbbrUtil() {
    }

    public static String abbr(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Pinyin.isChinese(c)) {
                String pinyin = Pinyin.toPinyin(c);
                if (!pinyin.isEmpty()) {
                    sb.append(Character.toUpperCase(pinyin.charAt(0)));
                }
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        String result = sb.toString();
        return result.isEmpty() ? null : result;
    }
}
