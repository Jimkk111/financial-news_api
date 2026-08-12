package com.financial.news.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录/注册响应
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录响应")
public class LoginResponse {
    @Schema(description = "JWT Token")
    private String accessToken;

    @Schema(description = "用户信息")
    private UserInfo user;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "用户简要信息")
    public static class UserInfo {
        private Integer id;
        private String uid;
        private String displayId;
        private String username;
        private String email;
        private String avatar;
    }
}
