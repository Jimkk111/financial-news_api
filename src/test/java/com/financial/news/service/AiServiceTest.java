package com.financial.news.service;

import com.financial.news.dto.request.AiChatRequest;
import com.financial.news.entity.AiMessage;
import com.financial.news.entity.AiSession;
import com.financial.news.mapper.AiMessageMapper;
import com.financial.news.mapper.AiSessionMapper;
import com.financial.news.service.ai.OpenAiCompatibleClient;
import com.financial.news.service.ai.OpenAiCompatibleClient.ChatParam;
import com.financial.news.service.ai.OpenAiCompatibleClient.ChatResult;
import com.financial.news.service.ai.OpenAiCompatibleClient.StreamCallback;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
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

    @Mock
    private OpenAiCompatibleClient aiClient;

    private AiService aiService;
    private AiSession session;

    @BeforeEach
    void setUp() {
        session = AiSession.builder()
                .id(42)
                .sessionId(SESSION_ID)
                .userId(USER_ID)
                .build();
        when(aiSessionMapper.selectBySessionId(SESSION_ID)).thenReturn(session);
        aiService = new AiService(aiSessionMapper, aiMessageMapper, aiClient);
    }

    @Test
    void chatPersistsOnlyCurrentUserMessageForEachRequest() {
        List<List<ChatParam>> contexts = new ArrayList<>();
        when(aiClient.chat(anyList(), anyBoolean())).thenAnswer(invocation -> {
            contexts.add(new ArrayList<>(invocation.getArgument(0)));
            return new ChatResult("reply-" + contexts.size(), "", List.of());
        });

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
        assertEquals("第一轮问题", contexts.get(1).get(0).content());
        assertEquals("reply-1", contexts.get(1).get(3).content());
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
        doAnswer(invocation -> {
            StreamCallback callback = invocation.getArgument(1);
            callback.onContent("stream-reply");
            return new ChatResult("stream-reply", "", List.of());
        }).when(aiClient).stream(anyList(), any(StreamCallback.class), anyBoolean());

        SseEmitter emitter = aiService.chatStream(USER_ID, request(
                userMessage("历史问题"),
                assistantMessage("历史回复"),
                userMessage("当前问题")));

        assertTrue(assistantSaved.await(5, TimeUnit.SECONDS));
        assertNotNull(emitter);

        ArgumentCaptor<AiMessage> messageCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper, times(2)).insert(messageCaptor.capture());
        assertEquals(List.of("当前问题", "stream-reply"),
                messageCaptor.getAllValues().stream().map(AiMessage::getContent).toList());
    }

    @Test
    void chatWithWebSearchInjectsDateSystemPromptAndPassesFlag() {
        AtomicReference<Boolean> flag = new AtomicReference<>();
        List<ChatParam> params = new ArrayList<>();
        when(aiClient.chat(anyList(), anyBoolean())).thenAnswer(invocation -> {
            params.addAll(invocation.getArgument(0));
            flag.set(invocation.getArgument(1));
            return new ChatResult("搜索回复", "", List.of());
        });

        AiChatRequest req = request(userMessage("今天A股行情"));
        req.setWebSearch(true);
        aiService.chat(USER_ID, req);

        assertTrue(flag.get());
        assertEquals("system", params.get(0).role());
        assertTrue(params.get(0).content().contains("今天是"));
        assertEquals("user", params.get(1).role());
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
