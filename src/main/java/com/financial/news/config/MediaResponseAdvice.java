package com.financial.news.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financial.news.common.Result;
import com.financial.news.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 业务控制器完成授权后，只对出站 JSON 副本签名，数据库和 Redis 保留稳定地址。 */
@ControllerAdvice
@RequiredArgsConstructor
public class MediaResponseAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectMapper objectMapper;
    private final MediaService mediaService;
    private static final Set<String> CONTROLLERS = Set.of("NewsController", "DraftController", "UserController",
            "FavoriteController", "HistoryController", "MediaController");
    private static final Set<String> FIELDS = Set.of("src", "poster", "coverImage", "imageUrl");

    @Override
    public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return CONTROLLERS.contains(method.getContainingClass().getSimpleName())
                && MappingJackson2HttpMessageConverter.class.isAssignableFrom(converter);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
                                  Class<? extends HttpMessageConverter<?>> converter,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof Result<?> result) || !"200".equals(result.getCode()) || result.getData() == null) return body;
        JsonNode copy = objectMapper.valueToTree(body);
        boolean upload = method.getContainingClass().getSimpleName().equals("MediaController");
        transform(copy.get("data"), upload, new HashMap<>());
        // 签名地址不能被共享代理缓存，权限和签名有效期以当前请求为准。
        response.getHeaders().setCacheControl("private, no-store");
        return copy;
    }

    private void transform(JsonNode node, boolean upload, Map<String, String> signed) {
        if (node == null) return;
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                String name = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isTextual() && (FIELDS.contains(name) || upload && "url".equals(name))) {
                    String url = value.asText();
                    object.put(name, signed.computeIfAbsent(url, mediaService::accessUrl));
                } else transform(value, upload, signed);
            });
        } else if (node.isArray()) node.forEach(child -> transform(child, upload, signed));
    }
}
