package com.financial.news.controller;

import com.financial.news.common.Result;
import com.financial.news.dto.request.*;
import com.financial.news.dto.response.LoginResponse;
import com.financial.news.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 *
 * @author financial-news
 * @since 1.0.0
 */
@Tag(name = "认证模块", description = "用户登录、注册、验证码、密码重置")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户登录", description = "支持用户名或邮箱登录，返回 JWT Token")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.okMsg("登出成功");
    }

    @Operation(summary = "发送验证码", description = "发送6位数字验证码到邮箱，有效期5分钟")
    @PostMapping("/send-code")
    public Result<Void> sendCode(@Valid @RequestBody SendCodeRequest request) {
        authService.sendVerificationCode(request);
        return Result.okMsg("验证码已发送");
    }

    @Operation(summary = "重置密码", description = "通过验证码重置密码")
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return Result.okMsg("密码重置成功");
    }
}
