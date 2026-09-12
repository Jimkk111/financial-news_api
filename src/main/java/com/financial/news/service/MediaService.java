package com.financial.news.service;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.utils.FileTypes;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 媒体文件上传服务：新闻配图/视频存储到阿里云 OSS
 * <p>类型以魔数校验为准（Content-Type 可伪造），返回可直接写入
 * News.imageUrl / Draft.coverImage / ImageBlock / VideoBlock 的公开访问 URL</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
public class MediaService {

    @Value("${upload.oss.endpoint:}")
    private String endpoint;

    @Value("${upload.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${upload.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${upload.oss.bucket:}")
    private String bucket;

    @Value("${upload.oss.dir:media}")
    private String ossDir;

    /** 可选自定义/CDN 域名，为空则拼 bucket 外网域名 */
    @Value("${upload.oss.custom-domain:}")
    private String customDomain;

    @Value("${upload.media.image-max-size:10485760}")
    private long imageMaxSize;

    @Value("${upload.media.video-max-size:209715200}")
    private long videoMaxSize;

    /** 懒加载：未配置 OSS 时不创建客户端，保证无 OSS 配置的环境正常启动 */
    private volatile OSS ossClient;

    /**
     * 上传图片（jpeg/png/gif/webp）
     */
    public String uploadImage(MultipartFile file) {
        return upload(file, Kind.IMAGE, imageMaxSize);
    }

    /**
     * 上传视频（mp4/mov、webm/mkv）
     */
    public String uploadVideo(MultipartFile file) {
        return upload(file, Kind.VIDEO, videoMaxSize);
    }

    private String upload(MultipartFile file, Kind kind, long maxSize) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_FILE);
        }
        if (file.getSize() > maxSize) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        byte[] header = readHeader(file);
        String ext = kind == Kind.IMAGE
                ? FileTypes.detectImageExtension(header)
                : FileTypes.detectVideoExtension(header);
        if (ext == null) {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
        }

        LocalDate now = LocalDate.now();
        String key = String.format("%s/%s/%d/%02d/%d_%s.%s",
                ossDir, kind.folder(), now.getYear(), now.getMonthValue(),
                System.currentTimeMillis(),
                UUID.randomUUID().toString().substring(0, 8),
                ext);

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(FileTypes.contentType(ext));

        try (InputStream in = file.getInputStream()) {
            client().putObject(bucket, key, in, metadata);
        } catch (OSSException | ClientException | IOException e) {
            log.error("媒体文件上传失败: kind={} size={} key={}", kind, file.getSize(), key, e);
            throw new BusinessException(ErrorCode.MEDIA_UPLOAD_FAILED);
        }
        return publicUrl(key);
    }

    private OSS client() {
        OSS client = ossClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (ossClient == null) {
                if (isBlank(endpoint) || isBlank(bucket) || isBlank(accessKeyId) || isBlank(accessKeySecret)) {
                    throw new BusinessException(ErrorCode.MEDIA_NOT_CONFIGURED);
                }
                ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            }
            return ossClient;
        }
    }

    /** 公开访问 URL：优先自定义域名，否则 https://{bucket}.{endpoint 域名}/{key} */
    private String publicUrl(String key) {
        if (!isBlank(customDomain)) {
            return stripSchemeAndSlash(customDomain) + "/" + key;
        }
        return "https://" + bucket + "." + stripSchemeAndSlash(endpoint) + "/" + key;
    }

    private String stripSchemeAndSlash(String url) {
        String result = url.trim();
        if (result.startsWith("https://")) {
            result = result.substring(8);
        } else if (result.startsWith("http://")) {
            result = result.substring(7);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(12);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @PreDestroy
    public void shutdown() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    private enum Kind {
        IMAGE("images"),
        VIDEO("videos");

        private final String folder;

        Kind(String folder) {
            this.folder = folder;
        }

        String folder() {
            return folder;
        }
    }
}
