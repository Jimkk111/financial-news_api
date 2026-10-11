package com.financial.news.config;

import com.financial.news.dto.request.DraftCreateRequest;
import com.financial.news.dto.request.DraftUpdateRequest;
import com.financial.news.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;

/** 长签名 URL 先规范化，再校验封面地址长度；媒体所有权仍在业务层校验。 */
@ControllerAdvice
@RequiredArgsConstructor
public class DraftMediaRequestAdvice extends RequestBodyAdviceAdapter {
    private final MediaService mediaService;

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return type == DraftCreateRequest.class || type == DraftUpdateRequest.class;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter,
                                Type type, Class<? extends HttpMessageConverter<?>> converter) {
        if (body instanceof DraftCreateRequest create) create.setCoverImage(mediaService.stableUrl(create.getCoverImage()));
        if (body instanceof DraftUpdateRequest update) update.setCoverImage(mediaService.stableUrl(update.getCoverImage()));
        return body;
    }
}
