package com.financial.news.utils;

import java.util.HashSet;
import java.util.Set;

/**
 * 正文指纹工具（SimHash 64位）
 * <p>用于跨源/转载的近似去重：对正文归一化文本取 3-gram 字符片段，
 * FNV-1a 64位哈希后加权叠加得到指纹；两篇文章指纹海明距离 ≤ 阈值（建议 6）
 * 视为近似重复。纯确定性计算，不依赖外部服务。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
public final class FingerprintUtil {

    private FingerprintUtil() {}

    /**
     * 计算文本的 SimHash 指纹
     */
    public static long simhash(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return 0L;
        }
        int[] bits = new int[64];
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 3 <= normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 3));
        }
        for (String gram : grams) {
            long h = fnv1a64(gram);
            for (int i = 0; i < 64; i++) {
                bits[i] += ((h >>> i) & 1L) == 1L ? 1 : -1;
            }
        }
        long result = 0L;
        for (int i = 0; i < 64; i++) {
            if (bits[i] > 0) {
                result |= (1L << i);
            }
        }
        return result;
    }

    /**
     * 海明距离：≤ 6 通常可判定为近似重复
     */
    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /**
     * 归一化：去除空白与中英文标点，转小写，消除排版差异
     */
    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+", "")
                .toLowerCase();
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
