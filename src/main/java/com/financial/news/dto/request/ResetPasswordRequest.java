package com.financial.news.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 重置密码请求
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Schema(description = "重置密码请求")
public class ResetPasswordRequest {
    @NotBlank @Size(min = 1, max = 50)
    @Schema(description = "用户名")
    private String username;

    @NotBlank @Email @Size(max = 100)
    @Schema(description = "邮箱")
    private String email;

    @NotBlank @Pattern(regexp = "^\\d{6}$")
    @Schema(description = "6位数字验证码")
    private String code;

    @NotBlank @Size(min = 8, max = 128)
    @Schema(description = "新密码（必须包含字母和数字）")
    private String password;
}
