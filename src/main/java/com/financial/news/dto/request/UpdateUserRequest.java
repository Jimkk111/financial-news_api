package com.financial.news.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 更新用户信息请求
 */
@Data
@Schema(description = "更新用户信息请求")
public class UpdateUserRequest {
    @Size(min = 3, max = 50) @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$") private String username;
    @Email @Size(max = 100) private String email;
}
