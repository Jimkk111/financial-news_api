package com.financial.news.service;

import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.entity.AiMessage;
import com.financial.news.entity.AiSession;
import com.financial.news.mapper.AiMessageMapper;
import com.financial.news.mapper.AiSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    private static final Integer USER_ID = 7;
    private static final String SESSION_ID = "session-test";

    @Mock
    private AiSessionMapper aiSessionMapper;

    @Mock
    private AiMessageMapper aiMessageMapper;

    private AiService aiService;
    private AiSession session;

    @BeforeEach
    void setUp() {
        session = AiSession.builder()
                .id(42)
                .sessionId(SESSION_ID)
                .userId(USER_ID)
                .build();
        when(aiSessionMapper.selectOne(any())).thenReturn(session);
        aiService = org.mockito.Mockito.spy(new AiService(aiSessionMapper, aiMessageMapper));
    }

    @Test
    void chatPersistsOnlyCurrentUserMessageForEachRequest() {
        List<List<AiChatRequest.ChatMessage>> contexts = new ArrayList<>();
        doAnswer(invocation -> {
            contexts.add(new ArrayList<>(invocation.getArgument(0)));
            return "reply-" + contexts.size();
        }).when(aiService).callAiApi(anyList());

        aiService.chat(USER_ID, request(
                userMessage("第一轮问题"),
                assistantMessage("历史回复"),
                userMessage("第二轮问题")));
        aiService.chat(USER_ID, request(
                userMessage("第一轮问题"),
                assistantMessage("历史回复"),
                userMessage("第二轮问题"),
                assistantMessage("reply-1"),
                userMessage("第三轮问题")));

        ArgumentCaptor<AiMessage> messageCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper, times(4)).insert(messageCaptor.capture());
        List<AiMessage> savedMessages = messageCaptor.getAllValues();

        assertEquals(List.of("user", "assistant", "user", "assistant"),
                savedMessages.stream().map(AiMessage::getRole).toList());
        assertEquals(List.of("第二轮问题", "reply-1", "第三轮问题", "reply-2"),
                savedMessages.stream().map(AiMessage::getContent).toList());
        assertEquals(List.of(3, 5), contexts.stream().map(List::size).toList());
        assertEquals("第一轮问题", contexts.get(1).get(0).getContent());
        assertEquals("reply-1", contexts.get(1).get(3).getContent());
    }

    @Test
    void chatStreamPersistsOnlyCurrentUserAndAssistantMessage() throws Exception {
        CountDownLatch assistantSaved = new CountDownLatch(1);
        doAnswer(invocation -> {
            AiMessage message = invocation.getArgument(0);
            if ("assistant".equals(message.getRole())) {
                assistantSaved.countDown();
            }
            return 1;
        }).when(aiMessageMapper).insert(any(AiMessage.class));
        doAnswer(invocation -> "stream-reply").when(aiService).callAiApi(anyList());

        SseEmitter emitter = aiService.chatStream(USER_ID, request(
                userMessage("历史问题"),
                assistantMessage("历史回复"),
                userMessage("当前问题")));

        assertTrue(assistantSaved.await(5, TimeUnit.SECONDS));
        assertTrue(emitter != null);

        ArgumentCaptor<AiMessage> messageCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper, times(2)).insert(messageCaptor.capture());
        assertEquals(List.of("当前问题", "stream-reply"),
                messageCaptor.getAllValues().stream().map(AiMessage::getContent).toList());
    }

    private AiChatRequest request(AiChatRequest.ChatMessage... messages) {
        AiChatRequest request = new AiChatRequest();
        request.setSessionId(SESSION_ID);
        request.setMessages(List.of(messages));
        return request;
    }

    private AiChatRequest.ChatMessage userMessage(String content) {
        return message("user", content);
    }

    private AiChatRequest.ChatMessage assistantMessage(String content) {
        return message("assistant", content);
    }

    private AiChatRequest.ChatMessage message(String role, String content) {
        AiChatRequest.ChatMessage message = new AiChatRequest.ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
