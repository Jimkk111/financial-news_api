package com.financial.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

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
public class AuthService extends ServiceImpl<UserMapper, User> {

    private final UserMapper userMapper;
    private final VerificationCodeMapper verificationCodeMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JavaMailSender mailSender;

    /**
     * 发件人邮箱，163 等 SMTP 服务要求与登录账号一致，否则返回 553
     */
    @Value("${spring.mail.username}")
    private String mailFrom;

    /**
     * 用户登录（支持用户名或邮箱）
     */
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername())
                .or()
                .eq(User::getEmail, request.getUsername()));

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
        if (userMapper.exists(new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()))) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        // 校验邮箱唯一性
        if (userMapper.exists(new LambdaQueryWrapper<User>().eq(User::getEmail, request.getEmail()))) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS);
        }
        // 校验验证码
        verifyCode(request.getEmail(), request.getCode());

        // 校验密码规则（必须包含字母和数字）
        if (!request.getPassword().matches(".*[a-zA-Z].*") || !request.getPassword().matches(".*\\d.*")) {
            throw new BusinessException(ErrorCode.PASSWORD_RULE_MISMATCH);
        }

        // 创建用户
        User user = User.builder()
                .uid(IdGenerator.generateUid())
                .displayId(IdGenerator.generateDisplayId())
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build();
        userMapper.insert(user);

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUid(), user.getUsername());
        return buildLoginResponse(token, user);
    }

    /**
     * 发送邮箱验证码
     */
    @Transactional
    public void sendVerificationCode(SendCodeRequest request) {
        String code = IdGenerator.generateVerificationCode();
        VerificationCode vc = VerificationCode.builder()
                .email(request.getEmail())
                .code(code)
                .username(request.getUsername())
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        verificationCodeMapper.insert(vc);

        // 发送邮件
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(request.getEmail());
            message.setSubject("财经新闻 - 验证码");
            message.setText("您的验证码是：" + code + "，有效期5分钟。");
            mailSender.send(message);
            log.info("验证码已发送至 {}", request.getEmail());
        } catch (Exception e) {
            log.error("邮件发送失败: {}", e.getMessage());
            // 不抛出异常，实际生产可配置异步重试
        }
    }

    /**
     * 重置密码
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
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
     */
    private void verifyCode(String email, String code) {
        VerificationCode vc = verificationCodeMapper.selectOne(
                new LambdaQueryWrapper<VerificationCode>()
                        .eq(VerificationCode::getEmail, email)
                        .eq(VerificationCode::getCode, code)
                        .gt(VerificationCode::getExpiresAt, LocalDateTime.now())
        );
        if (vc == null) {
            throw new BusinessException(ErrorCode.INVALID_CODE);
        }
        // 验证成功后删除验证码
        verificationCodeMapper.deleteById(vc.getId());
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
