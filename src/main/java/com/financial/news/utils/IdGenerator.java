package com.financial.news.utils;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;

import java.util.UUID;

/**
 * ID 生成器
 * <p>用于生成各类自定义格式的 ID</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
public final class IdGenerator {

    private IdGenerator() {}

    /**
     * 生成用户公开 UID（格式：user-{uuid前8位}）
     */
    public static String generateUid() {
        return "user-" + uuid8();
    }

    /**
     * 生成用户展示 ID（格式：U{时间戳后6位}{3位随机数}）
     */
    public static String generateDisplayId() {
        String ts = String.valueOf(System.currentTimeMillis());
        String suffix = ts.substring(ts.length() - 6);
        String random = String.format("%03d", RandomUtil.randomInt(0, 999));
        return "U" + suffix + random;
    }

    /**
     * 生成草稿 ID（格式：draft-{uuid前8位}）
     */
    public static String generateDraftId() {
        return "draft-" + uuid8();
    }

    /**
     * 生成 AI 会话 ID（格式：session-{uuid前8位}）
     */
    public static String generateSessionId() {
        return "session-" + uuid8();
    }

    /**
     * 生成 6 位数字验证码
     */
    public static String generateVerificationCode() {
        return RandomUtil.randomNumbers(6);
    }

    /**
     * 获取 UUID 前 8 位（小写十六进制）
     */
    private static String uuid8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
