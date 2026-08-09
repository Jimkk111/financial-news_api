package com.financial.news.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

/**
 * AI 对话请求
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Schema(description = "AI对话请求")
public class AiChatRequest {
    @NotEmpty @Size(min = 1, max = 50)
    @Schema(description = "消息列表")
    private List<ChatMessage> messages;

    @Schema(description = "会话ID（不传则自动创建）", example = "session-a1b2c3d4")
    private String sessionId;

    @Schema(description = "是否流式返回", example = "false")
    private Boolean stream;

    @Data
    @Schema(description = "对话消息")
    public static class ChatMessage {
        @NotBlank
        @Schema(description = "角色：user/assistant/system", example = "user")
        private String role;

        @NotBlank @Size(min = 1, max = 4000)
        @Schema(description = "消息内容")
        private String content;
    }
}
