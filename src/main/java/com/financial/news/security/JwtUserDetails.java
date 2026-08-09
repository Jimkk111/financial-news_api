package com.financial.news.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * JWT 用户详情（用于 Spring Security 上下文）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Getter
@AllArgsConstructor
public class JwtUserDetails {

    /** 用户内部 ID */
    private final Integer userId;

    /** 用户公开 UID */
    private final String uid;

    /** 用户名 */
    private final String username;

    /**
     * 获取当前登录用户（从 SecurityContext 获取）
     */
    public static JwtUserDetails getCurrentUser() {
        Object principal = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        if (principal instanceof JwtUserDetails) {
            return (JwtUserDetails) principal;
        }
        return null;
    }
}
