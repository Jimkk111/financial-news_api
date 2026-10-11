package com.financial.news.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.common.GlobalExceptionHandler;
import com.financial.news.controller.MediaController;
import com.financial.news.utils.FileTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.financial.news.security.JwtUserDetails;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaUploadTest {
    private static final byte[] WEBP = HexFormat.of().parseHex("52494646100000005745425056503820");
    private static final byte[] MP4 = HexFormat.of().parseHex("000000186674797069736F6D");

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(new JwtUserDetails(7, "test", "test"), null, java.util.List.of()));
    }

    @AfterEach
    void logout() { SecurityContextHolder.clearContext(); }

    private MediaService service() {
        MediaService service = new MediaService();
        ReflectionTestUtils.setField(service, "endpoint", "https://oss-cn-hangzhou.aliyuncs.com/");
        ReflectionTestUtils.setField(service, "bucket", "test-bucket");
        ReflectionTestUtils.setField(service, "ossDir", "media");
        ReflectionTestUtils.setField(service, "imageMaxSize", 10L * 1024 * 1024);
        ReflectionTestUtils.setField(service, "videoMaxSize", 200L * 1024 * 1024);
        return service;
    }

    @Test
    void detectsWebpAtCorrectOffsetAndRejectsOtherRiffFiles() {
        assertEquals("webp", FileTypes.detectImageExtension(WEBP));
        assertNull(FileTypes.detectImageExtension("RIFF0000AVI ".getBytes(StandardCharsets.US_ASCII)));
        assertNull(FileTypes.detectImageExtension("RIFFWEBP".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void imageAndVideoMultipartRequestsUploadToOssAndReturnAbsoluteUrls() throws Exception {
        MediaService service = service();
        OSS oss = mock(OSS.class);
        ReflectionTestUtils.setField(service, "ossClient", oss);
        var mvc = MockMvcBuilders.standaloneSetup(new MediaController(service)).build();
        mvc.perform(multipart("/api/media/image")
                        .file(new MockMultipartFile("file", "test.webp", "image/webp", WEBP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith(
                        "https://test-bucket.oss-cn-hangzhou.aliyuncs.com/media/users/7/images/")));
        mvc.perform(multipart("/api/media/video")
                        .file(new MockMultipartFile("file", "test.mp4", "video/mp4", MP4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.endsWith(".mp4")));
        verify(oss, times(2)).putObject(eq("test-bucket"), anyString(), any(InputStream.class), any(ObjectMetadata.class));
    }

    @Test
    void customDomainPreservesProtocolAndDefaultsToHttps() {
        MediaService service = service();
        ReflectionTestUtils.setField(service, "ossClient", mock(OSS.class));
        for (String domain : new String[]{"https://cdn.example.com/", "cdn.example.com/"}) {
            ReflectionTestUtils.setField(service, "customDomain", domain);
            assertTrue(service.uploadImage(new MockMultipartFile("file", WEBP), 7)
                    .startsWith("https://cdn.example.com/media/users/7/images/"));
        }
    }

    @Test
    void missingOssConfigurationReturnsExplicit503ForBothKinds() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new MediaController(service()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(multipart("/api/media/image").file(new MockMultipartFile("file", WEBP)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_CONFIGURED"));
        mvc.perform(multipart("/api/media/video").file(new MockMultipartFile("file", MP4)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_CONFIGURED"));
    }

    @Test
    void rejectsUnsupportedAndOversizedFilesBeforeStorage() {
        MediaService service = service();
        BusinessException invalid = assertThrows(BusinessException.class,
                () -> service.uploadVideo(new MockMultipartFile("file", "test.ogg", "video/ogg", WEBP), 7));
        assertEquals(ErrorCode.INVALID_FILE_TYPE, invalid.getErrorCode());
        ReflectionTestUtils.setField(service, "imageMaxSize", 8L);
        BusinessException oversized = assertThrows(BusinessException.class,
                () -> service.uploadImage(new MockMultipartFile("file", WEBP), 7));
        assertEquals(ErrorCode.FILE_TOO_LARGE, oversized.getErrorCode());
    }
}
