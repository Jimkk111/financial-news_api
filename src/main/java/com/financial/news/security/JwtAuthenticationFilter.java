package com.financial.news.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器
 * <p>从请求 Cookie 中提取 Token 并进行验证（兼容 Authorization 头）</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /** Swagger/Knife4j 相关路径，跳过 JWT 认证 */
    private static final String[] SWAGGER_WHITELIST = {
            "/doc.html", "/swagger-ui.html", "/swagger-ui/**",
            "/v3/api-docs/**", "/webjars/**", "/swagger-resources/**",
            "/favicon.ico"
    };
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        for (String pattern : SWAGGER_WHITELIST) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (StringUtils.hasText(token)) {
            try {
                if (jwtTokenProvider.validateToken(token)) {
                    Integer userId = jwtTokenProvider.getUserIdFromToken(token);
                    String uid = jwtTokenProvider.getUidFromToken(token);
                    String username = jwtTokenProvider.getUsernameFromToken(token);

                    JwtUserDetails userDetails = new JwtUserDetails(userId, uid, username);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userDetails, null, Collections.emptyList());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    // 无效/过期 Token 不直接拦截：清除认证上下文与 Cookie 后放行，
                    // 保证公开接口（如 GET /api/news）对持有旧 Token 的用户仍然可用；
                    // 受保护接口由安全链返回 401
                    log.warn("JWT 无效或已过期，已清除认证上下文");
                    clearAuthCookie(response);
                }
            } catch (Exception e) {
                log.warn("JWT 认证失败: {}", e.getMessage());
                SecurityContextHolder.clearContext();
                clearAuthCookie(response);
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 清除无效的认证 Cookie
     */
    private void clearAuthCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(jwtTokenProvider.getCookieName(), null);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    /**
     * 提取 JWT Token
     * <p>优先从 Cookie 中提取，同时兼容旧的 Authorization 头方式</p>
     */
    private String extractToken(HttpServletRequest request) {
        // 优先从 HttpOnly Cookie 提取
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (jwtTokenProvider.getCookieName().equals(cookie.getName())
                        && StringUtils.hasText(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }
        // 兼容：仍支持 Authorization: Bearer <token> 方式
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
