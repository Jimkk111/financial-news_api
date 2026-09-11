package com.financial.news.service;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.request.*;
import com.financial.news.dto.response.LoginResponse;
import com.financial.news.entity.User;
import com.financial.news.entity.VerificationCode;
import com.financial.news.mapper.UserMapper;
import com.financial.news.mapper.VerificationCodeMapper;
import com.financial.news.security.JwtTokenProvider;
import com.financial.news.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 认证服务
 * <p>负责用户登录、注册、验证码发送、密码重置等认证相关业务</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final VerificationCodeMapper verificationCodeMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JavaMailSender mailSender;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CODE_INTERVAL_KEY = "auth:code:interval:";
    private static final String CODE_DAILY_KEY = "auth:code:daily:";
    private static final String CODE_FAIL_KEY = "auth:code:fail:";
    private static final long CODE_MAX_ATTEMPTS = 5;

    /**
     * 发件人邮箱，163 等 SMTP 服务要求与登录账号一致，否则返回 553
     */
    @Value("${spring.mail.username}")
    private String mailFrom;

    /**
     * 用户登录（支持用户名或邮箱）
     */
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectByUsernameOrEmail(request.getUsername());

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUid(), user.getUsername());
        return buildLoginResponse(token, user);
    }

    /**
     * 用户注册
     */
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        // 校验用户名唯一性
        if (userMapper.countByUsername(request.getUsername()) > 0) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        // 校验邮箱唯一性
        if (userMapper.countByEmail(request.getEmail()) > 0) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS);
        }
        // 校验验证码
        verifyCode(request.getEmail(), request.getCode());

        // 校验密码规则（必须包含字母和数字）
        if (!request.getPassword().matches(".*[a-zA-Z].*") || !request.getPassword().matches(".*\\d.*")) {
            throw new BusinessException(ErrorCode.PASSWORD_RULE_MISMATCH);
        }

        // 创建用户（并发下唯一性由数据库唯一索引兜底，冲突转为业务错误）
        User user = User.builder()
                .uid(IdGenerator.generateUid())
                .displayId(IdGenerator.generateDisplayId())
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build();
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(userMapper.countByUsername(request.getUsername()) > 0
                    ? ErrorCode.USERNAME_EXISTS : ErrorCode.EMAIL_EXISTS);
        }

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUid(), user.getUsername());
        return buildLoginResponse(token, user);
    }

    /**
     * 发送邮箱验证码
     * <p>带发送频率限制（同一邮箱 60 秒 1 条、24 小时 10 条）；
     * 邮件发送成功后才落库验证码，发送失败向上抛错而非伪装成功</p>
     */
    public void sendVerificationCode(SendCodeRequest request) {
        String email = request.getEmail();
        checkSendRateLimit(email);

        String code = IdGenerator.generateVerificationCode();
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("财经新闻 - 验证码");
            message.setText("您的验证码是：" + code + "，有效期5分钟。");
            mailSender.send(message);
        } catch (Exception e) {
            log.error("邮件发送失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码发送失败，请稍后重试");
        }
        log.info("验证码已发送至 {}", email);

        VerificationCode vc = VerificationCode.builder()
                .email(email)
                .code(code)
                .username(request.getUsername())
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        verificationCodeMapper.insert(vc);
    }

    /**
     * 验证码发送频率限制：60 秒 1 条、24 小时 10 条（Redis 不可用时降级放行）
     */
    private void checkSendRateLimit(String email) {
        try {
            Boolean tooFast = redisTemplate.opsForValue()
                    .setIfAbsent(CODE_INTERVAL_KEY + email, 1, Duration.ofSeconds(60));
            if (Boolean.FALSE.equals(tooFast)) {
                throw new BusinessException(ErrorCode.CODE_SEND_TOO_FREQUENT);
            }
            Long daily = redisTemplate.opsForValue().increment(CODE_DAILY_KEY + email);
            if (daily != null && daily == 1) {
                redisTemplate.expire(CODE_DAILY_KEY + email, Duration.ofHours(24));
            }
            if (daily != null && daily > 10) {
                throw new BusinessException(ErrorCode.CODE_SEND_TOO_FREQUENT, "今日验证码发送次数已达上限");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis 验证码限流不可用，降级放行: {}", e.getMessage());
        }
    }

    /**
     * 重置密码
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userMapper.selectByUsername(request.getUsername());
        if (user == null || !user.getEmail().equals(request.getEmail())) {
            throw new BusinessException(ErrorCode.USERNAME_EMAIL_MISMATCH);
        }
        verifyCode(request.getEmail(), request.getCode());

        if (!request.getPassword().matches(".*[a-zA-Z].*") || !request.getPassword().matches(".*\\d.*")) {
            throw new BusinessException(ErrorCode.PASSWORD_RULE_MISMATCH);
        }

        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userMapper.updateById(user);
    }

    /**
     * 校验验证码
     * <p>同一邮箱累计输错 5 次即销毁验证码并拒绝后续校验，防止暴力枚举
     * （Redis 不可用时降级为无次数限制）</p>
     */
    private void verifyCode(String email, String code) {
        try {
            Object cached = redisTemplate.opsForValue().get(CODE_FAIL_KEY + email);
            long fails = cached instanceof Number n ? n.longValue() : 0;
            if (fails >= CODE_MAX_ATTEMPTS) {
                // 锁定期间直接拒绝，不泄露验证码是否正确
                throw new BusinessException(ErrorCode.INVALID_CODE, "验证码错误次数过多，请重新获取");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis 验证码防爆破不可用，降级放行: {}", e.getMessage());
        }

        VerificationCode vc = verificationCodeMapper.selectValid(email, code, LocalDateTime.now());
        if (vc == null) {
            recordCodeFailure(email);
            throw new BusinessException(ErrorCode.INVALID_CODE);
        }
        // 验证成功后删除验证码与失败计数
        verificationCodeMapper.deleteById(vc.getId());
        try {
            redisTemplate.delete(CODE_FAIL_KEY + email);
        } catch (Exception ignored) {
        }
    }

    private void recordCodeFailure(String email) {
        try {
            Long fails = redisTemplate.opsForValue().increment(CODE_FAIL_KEY + email);
            if (fails != null && fails == 1) {
                redisTemplate.expire(CODE_FAIL_KEY + email, Duration.ofMinutes(5));
            }
            if (fails != null && fails >= CODE_MAX_ATTEMPTS) {
                // 连续错误达到上限，销毁有效验证码
                verificationCodeMapper.deleteByEmail(email);
            }
        } catch (Exception e) {
            log.warn("Redis 验证码失败计数不可用: {}", e.getMessage());
        }
    }

    private LoginResponse buildLoginResponse(String token, User user) {
        return LoginResponse.builder()
                .accessToken(token)
                .user(LoginResponse.UserInfo.builder()
                        .id(user.getId())
                        .uid(user.getUid())
                        .displayId(user.getDisplayId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .avatar(user.getAvatar())
                        .build())
                .build();
    }
}
