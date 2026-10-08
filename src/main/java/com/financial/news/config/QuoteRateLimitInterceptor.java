package com.financial.news.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.common.ErrorCode;
import com.financial.news.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 行情公开接口限流拦截器（防滥用）：Redis 固定窗口，按 IP 计数。
 * 超限返回 429 + RATE_LIMIT_EXCEEDED。
 *
 * @author financial-news
 * @since 1.0.0
 */
@Component
@RequiredArgsConstructor
public class QuoteRateLimitInterceptor implements HandlerInterceptor {

    private static final String KEY_PREFIX = "rate:quote:";

    private final StringRedisTemplate redisTemplate;
    private final QuoteProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        QuoteProperties.RateLimit config = properties.getRateLimit();
        if (!config.isEnabled()) {
            return true;
        }
        String key = KEY_PREFIX + resolveClientIp(request);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(config.getWindowSeconds()));
        }
        if (count != null && count > config.getMaxRequests()) {
            response.setStatus(ErrorCode.RATE_LIMIT_EXCEEDED.getHttpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ErrorCode.RATE_LIMIT_EXCEEDED)));
            return false;
        }
        return true;
    }

    /** IP 口径与 NewsController.resolveViewerKey 一致：X-Forwarded-For 首个值优先 */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
