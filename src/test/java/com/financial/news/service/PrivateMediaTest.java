package com.financial.news.service;

import com.aliyun.oss.OSS;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.common.*;
import com.financial.news.config.MediaResponseAdvice;
import com.financial.news.config.DraftMediaRequestAdvice;
import com.financial.news.controller.DraftController;
import com.financial.news.controller.MediaAccessController;
import com.financial.news.controller.NewsController;
import com.financial.news.controller.MediaController;
import com.financial.news.dto.request.DraftCreateRequest;
import com.financial.news.dto.request.DraftUpdateRequest;
import com.financial.news.entity.Draft;
import com.financial.news.entity.News;
import com.financial.news.mapper.*;
import com.financial.news.model.content.*;
import com.financial.news.security.JwtUserDetails;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URL;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrivateMediaTest {
    private static final String BASE = "https://test-bucket.oss-cn-hangzhou.aliyuncs.com/";
    private static final String OWN = BASE + "media/users/7/images/test.webp";
    private MediaService media;
    private OSS oss;
    private NewsMapper news;
    private DraftMapper drafts;
    private MediaAccessService access;

    @BeforeEach
    void setup() throws Exception {
        SecurityContextHolder.clearContext();
        media = new MediaService();
        ReflectionTestUtils.setField(media, "endpoint", "https://oss-cn-hangzhou.aliyuncs.com");
        ReflectionTestUtils.setField(media, "bucket", "test-bucket");
        ReflectionTestUtils.setField(media, "ossDir", "media");
        oss = mock(OSS.class);
        ReflectionTestUtils.setField(media, "ossClient", oss);
        when(oss.generatePresignedUrl(eq("test-bucket"), anyString(), any(Date.class)))
                .thenAnswer(inv -> new URL(BASE + inv.getArgument(1) + "?Expires=1999999999&Signature=test"));
        news = mock(NewsMapper.class);
        drafts = mock(DraftMapper.class);
        access = new MediaAccessService(media, news, drafts);
    }

    @AfterEach
    void logout() { SecurityContextHolder.clearContext(); }

    @Test
    void actualSdkGeneratesV4ReadSignaturesWithoutNetwork() {
        ReflectionTestUtils.setField(media, "ossClient", null);
        ReflectionTestUtils.setField(media, "accessKeyId", "test-access-key");
        ReflectionTestUtils.setField(media, "accessKeySecret", "test-secret-for-offline-signing");
        try {
            String signed = media.accessUrl(OWN);
            assertTrue(signed.contains("x-oss-signature-version=OSS4-HMAC-SHA256"));
            assertTrue(signed.contains("x-oss-signature="));
            assertEquals(OWN, media.stableUrl(signed));
            assertFalse(signed.contains("test-secret-for-offline-signing"));
        } finally { media.shutdown(); }
    }

    @Test
    void signsOnlyExactConfiguredBucketMediaPathsAndPreservesExternalQueries() {
        for (String url : List.of("https://example.com/image?a=1", BASE + "private/file",
                "https://test-bucket.oss-cn-hangzhou.aliyuncs.com.evil.com/media/x",
                BASE + "media/%2e%2e/private/x", BASE + "media/../private/x")) {
            assertNull(media.objectKey(url));
            assertEquals(url, media.accessUrl(url));
        }
        verifyNoInteractions(oss);
    }

    @Test
    void saveStripsSignaturesFromCoverNestedImageVideoAndPoster() {
        String signed = media.accessUrl(OWN);
        String video = BASE + "media/users/7/videos/test.mp4";
        var image = ImageBlock.builder().src(signed).build();
        var draft = Draft.builder().coverImage(signed).contentJson(List.of(
                BlockquoteBlock.builder().children(List.of(image)).build(),
                VideoBlock.builder().src(media.accessUrl(video)).poster(signed).build())).build();
        access.prepareForSave(draft, 7, Set.of());
        assertEquals(OWN, draft.getCoverImage());
        assertFalse(com.financial.news.utils.ContentCodec.toJson(draft.getContentJson()).contains("Signature"));
        assertFalse(draft.getContent().contains("Signature"));
        assertTrue(image.getSrc().contains("Signature")); // 请求对象不被深拷贝后的转换污染
    }

    @Test
    void cannotInjectAnotherUsersPrivateMediaAndLegacyReferencesRemainEditable() {
        Draft foreign = Draft.builder().coverImage(BASE + "media/users/8/images/secret.webp").build();
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> access.prepareForSave(foreign, 7, Set.of())).getErrorCode());
        String legacy = BASE + "media/images/old.webp";
        Draft old = Draft.builder().coverImage(legacy + "?Signature=expired").build();
        access.prepareForSave(old, 7, Set.of(legacy));
        assertEquals(legacy, old.getCoverImage());
    }

    @Test
    void anonymousCanRenewOnlyMediaActuallyReferencedByPublishedNews() throws Exception {
        when(news.selectById(12)).thenReturn(News.builder().id(12).imageUrl(OWN).build());
        var mvc = MockMvcBuilders.standaloneSetup(new MediaAccessController(access))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/media/access").param("newsId", "12").param("url", OWN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.containsString("Signature=")));
        mvc.perform(get("/api/media/access").param("newsId", "12")
                        .param("url", BASE + "media/users/7/images/secret.webp"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/media/access").param("newsId", "13").param("url", OWN))
                .andExpect(status().isNotFound());
    }

    @Test
    void draftOwnerAndFreshUploadOwnerChecksPrecedeSigning() {
        Draft draft = Draft.builder().id("draft-test").userId(7).coverImage(OWN).build();
        when(drafts.selectById("draft-test")).thenReturn(draft);
        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BusinessException.class,
                () -> access.refresh(OWN, null, "draft-test", null)).getErrorCode());
        assertEquals(ErrorCode.DRAFT_NOT_OWNER, assertThrows(BusinessException.class,
                () -> access.refresh(OWN, null, "draft-test", 8)).getErrorCode());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> access.refresh(OWN, null, null, 8)).getErrorCode());
        verifyNoInteractions(oss);
        assertTrue(access.refresh(OWN, null, "draft-test", 7).contains("Signature="));
        assertTrue(access.refresh(OWN, null, null, 7).contains("Signature="));
    }

    @Test
    void responseAdviceSignsDetachedPayloadWithoutChangingCachedNews() throws Exception {
        News cached = News.builder().id(12).imageUrl(OWN).contentJson(List.of(
                ImageBlock.builder().src(OWN).build())).build();
        var body = Result.ok(cached);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var advice = new MediaResponseAdvice(mapper, media);
        var method = new MethodParameter(NewsController.class.getMethod("getNewsDetail", Integer.class), -1);
        var response = new MockHttpServletResponse();
        JsonNode signed = (JsonNode) advice.beforeBodyWrite(body, method, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(response));
        assertTrue(signed.path("data").path("imageUrl").asText().contains("Signature="));
        assertTrue(signed.path("data").path("content").get(0).path("src").asText().contains("Signature="));
        assertEquals("image", signed.path("data").path("content").get(0).path("type").asText());
        assertEquals(OWN, cached.getImageUrl());
        assertEquals(OWN, ((ImageBlock) cached.getContentJson().get(0)).getSrc());
        verify(oss, times(1)).generatePresignedUrl(eq("test-bucket"), anyString(), any(Date.class));
    }

    @Test
    void uploadHttpResponseContainsSignedPreview() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtUserDetails(7, "test", "test"), null, List.of()));
        ReflectionTestUtils.setField(media, "imageMaxSize", 10485760L);
        var mvc = MockMvcBuilders.standaloneSetup(new MediaController(media))
                .setControllerAdvice(new MediaResponseAdvice(new ObjectMapper(), media)).build();
        mvc.perform(multipart("/api/media/image").file(new MockMultipartFile("file", "test.webp", "image/webp",
                        HexFormat.of().parseHex("52494646100000005745425056503820"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.containsString("Signature=")));
    }

    @Test
    void draftCreateUpdateAndPublishPersistStableReferences() {
        var draftTags = mock(DraftTagMapper.class);
        var newsTags = mock(NewsTagMapper.class);
        var tags = mock(TagMapper.class);
        var service = new DraftService(drafts, news, draftTags, newsTags, tags, access);
        DraftCreateRequest create = new DraftCreateRequest();
        create.setTitle("Private media");
        create.setCoverImage(media.accessUrl(OWN));
        create.setContentJson(List.of(ImageBlock.builder().src(media.accessUrl(OWN)).build()));
        Draft draft = service.createDraft(7, create);
        assertEquals(OWN, draft.getCoverImage());
        verify(drafts).insert(argThat(value -> !value.getContent().contains("Signature=")));
        when(drafts.selectById(draft.getId())).thenReturn(draft);
        DraftUpdateRequest update = new DraftUpdateRequest();
        update.setCoverImage(media.accessUrl(OWN));
        update.setContentJson(create.getContentJson());
        service.updateDraft(draft.getId(), 7, update);
        assertEquals(OWN, draft.getCoverImage());
        assertFalse(draft.getContent().contains("Signature="));
        News published = service.publishDraft(draft.getId(), 7);
        assertEquals(OWN, published.getImageUrl());
        assertFalse(published.getContent().contains("Signature="));
        assertEquals(OWN, ((ImageBlock) published.getContentJson().get(0)).getSrc());
    }

    @Test
    void longSignedCoverIsNormalizedBeforeRequestLengthValidation() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtUserDetails(7, "test", "test"), null, List.of()));
        var service = new DraftService(drafts, news, mock(DraftTagMapper.class), mock(NewsTagMapper.class), mock(TagMapper.class), access);
        var mvc = MockMvcBuilders.standaloneSetup(new DraftController(service))
                .setControllerAdvice(new DraftMediaRequestAdvice(media)).build();
        var request = new DraftCreateRequest();
        request.setTitle("Signed cover");
        request.setCoverImage(OWN + "?Signature=" + "x".repeat(600));
        mvc.perform(post("/api/drafts").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(request)))
                .andExpect(status().isCreated());
        verify(drafts).insert(argThat(draft -> OWN.equals(draft.getCoverImage())));
    }
}
