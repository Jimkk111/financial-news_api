package com.financial.news.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 发送验证码请求
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Schema(description = "发送验证码请求")
public class SendCodeRequest {
    @NotBlank @Email @Size(max = 100)
    @Schema(description = "邮箱", example = "john@example.com")
    private String email;

    @Size(max = 50)
    @Schema(description = "用户名（注册场景可选）")
    private String username;
}
