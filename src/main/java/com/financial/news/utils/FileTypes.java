package com.financial.news.utils;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * 文件魔数识别：上传文件类型以文件头为准（Content-Type 可伪造）
 *
 * @author financial-news
 * @since 1.0.0
 */
public final class FileTypes {

    private FileTypes() {
    }

    /** 图片魔数前缀（大写十六进制），key 为归一化扩展名 */
    private static final Map<String, String> IMAGE_MAGIC_NUMBERS = Map.of(
            "jpeg", "FFD8FF",
            "png", "89504E47",
            "gif", "474946",
            "webp", "52494646"
    );

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp",
            "mp4", "video/mp4",
            "webm", "video/webm"
    );

    /**
     * 识别图片真实类型，返回归一化扩展名（jpeg/png/gif/webp），不识别返回 null。
     * <p>webp 为 RIFF....WEBP 头</p>
     */
    public static String detectImageExtension(byte[] header) {
        if (header == null || header.length < 4) {
            return null;
        }
        String hex = HexFormat.of().formatHex(header).toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : IMAGE_MAGIC_NUMBERS.entrySet()) {
            if (hex.startsWith(entry.getValue())) {
                // RIFF 前缀同时命中 webp 与 avi，需进一步核对 WEBP 子标识
                if ("webp".equals(entry.getKey())) {
                    if (hex.length() >= 12 && hex.regionMatches(8, "57454250", 0, 8)) {
                        return "webp";
                    }
                    continue;
                }
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 识别视频真实类型，返回扩展名（mp4：含 ftyp 盒的 mp4/mov/m4a；webm：含 EBML 头的 webm/mkv），不识别返回 null
     */
    public static String detectVideoExtension(byte[] header) {
        if (header == null || header.length < 12) {
            return null;
        }
        // ftyp 盒：第 5-8 字节为 "ftyp"，覆盖 mp4/mov/m4a
        if (header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            return "mp4";
        }
        // EBML 头：1A 45 DF A3，覆盖 webm/mkv
        String hex = HexFormat.of().formatHex(header).toUpperCase(Locale.ROOT);
        if (hex.startsWith("1A45DFA3")) {
            return "webm";
        }
        return null;
    }

    /**
     * 扩展名对应的 HTTP Content-Type，未知返回 application/octet-stream
     */
    public static String contentType(String extension) {
        return CONTENT_TYPES.getOrDefault(extension.toLowerCase(Locale.ROOT), "application/octet-stream");
    }
}
