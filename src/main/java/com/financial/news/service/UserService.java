package com.financial.news.service;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.common.Result;
import com.financial.news.dto.request.UpdateUserRequest;
import com.financial.news.dto.response.UserResponse;
import com.financial.news.entity.News;
import com.financial.news.entity.User;
import com.financial.news.mapper.NewsMapper;
import com.financial.news.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户服务
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final NewsMapper newsMapper;

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    @Value("${upload.url-prefix:/uploads}")
    private String uploadUrlPrefix;

    @Value("${upload.avatar-max-size:2097152}")
    private long avatarMaxSize;

    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 获取当前用户信息
     */
    public UserResponse getCurrentUser(Integer userId) {
        User user = findById(userId);
        return buildUserResponse(user);
    }

    /**
     * 更新用户信息
     */
    @Transactional
    public UserResponse updateUser(Integer userId, UpdateUserRequest request) {
        User user = findById(userId);
        boolean updated = false;

        if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
            if (userMapper.countByUsername(request.getUsername()) > 0) {
                throw new BusinessException(ErrorCode.USERNAME_EXISTS);
            }
            user.setUsername(request.getUsername());
            updated = true;
        }
        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            if (userMapper.countByEmail(request.getEmail()) > 0) {
                throw new BusinessException(ErrorCode.EMAIL_EXISTS);
            }
            user.setEmail(request.getEmail());
            updated = true;
        }

        if (!updated) {
            throw new BusinessException(ErrorCode.NO_UPDATE_DATA);
        }

        try {
            userMapper.updateById(user);
        } catch (DuplicateKeyException e) {
            // 预检与写入之间的并发窗口由数据库唯一索引兜底
            throw new BusinessException(userMapper.countByUsername(user.getUsername()) > 0
                    ? ErrorCode.USERNAME_EXISTS : ErrorCode.EMAIL_EXISTS);
        }
        return buildUserResponse(user);
    }

    /** 图片魔数白名单，Content-Type 请求头可伪造，以文件头为准 */
    private static final Map<String, String> IMAGE_MAGIC_NUMBERS = Map.of(
            "jpeg", "FFD8FF",
            "png", "89504E47",
            "gif", "474946",
            "webp", "52494646"
    );

    /**
     * 上传头像
     * <p>先落库后写文件，写文件失败回滚事务并清理已写文件；
     * 文件类型以魔数校验为准（Content-Type 可伪造）</p>
     */
    @Transactional
    public String uploadAvatar(Integer userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_FILE);
        }
        if (file.getSize() > avatarMaxSize) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        String ext = detectImageExtension(file);
        if (ext == null) {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
        }

        LocalDate now = LocalDate.now();
        String relativePath = String.format("avatars/%d/%02d/%d_%s.%s",
                now.getYear(), now.getMonthValue(),
                System.currentTimeMillis(),
                UUID.randomUUID().toString().substring(0, 8),
                ext);

        // 先落库，写文件失败抛异常回滚，不留悬空的 avatar 引用
        String avatarUrl = uploadUrlPrefix + "/" + relativePath.replace("\\", "/");
        User user = findById(userId);
        String oldAvatar = user.getAvatar();
        user.setAvatar(avatarUrl);
        userMapper.updateById(user);

        Path targetPath = null;
        try {
            targetPath = Paths.get(uploadDir, relativePath);
            Files.createDirectories(targetPath.getParent());
            file.transferTo(targetPath.toFile());
        } catch (IOException e) {
            log.error("头像上传失败", e);
            // 清理可能已写入的部分文件，事务随异常回滚
            if (targetPath != null) {
                try {
                    Files.deleteIfExists(targetPath);
                } catch (IOException ignored) {
                }
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件上传失败");
        }

        // 落库成功且新文件已写入，事务提交后清理旧头像文件（异步，避免拖长事务）
        if (oldAvatar != null && oldAvatar.startsWith(uploadUrlPrefix + "/avatars/")) {
            String oldRelative = oldAvatar.substring(uploadUrlPrefix.length() + 1);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        Files.deleteIfExists(Paths.get(uploadDir, oldRelative));
                    } catch (IOException e) {
                        log.warn("旧头像文件清理失败: {}", oldRelative);
                    }
                }
            });
        }
        return avatarUrl;
    }

    /**
     * 通过文件魔数识别图片真实类型（webp 为 RIFF....WEBP 头）
     */
    private String detectImageExtension(MultipartFile file) {
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(12);
        } catch (IOException e) {
            return null;
        }
        if (header.length < 4) {
            return null;
        }
        String hex = HexFormat.of().formatHex(header).toUpperCase();
        if (hex.startsWith(IMAGE_MAGIC_NUMBERS.get("jpeg"))) {
            return "jpeg";
        }
        if (hex.startsWith(IMAGE_MAGIC_NUMBERS.get("png"))) {
            return "png";
        }
        if (hex.startsWith(IMAGE_MAGIC_NUMBERS.get("gif"))) {
            return "gif";
        }
        // RIFF....WEBP
        if (hex.startsWith("52494646") && hex.length() >= 12 && hex.regionMatches(8, "57454250", 0, 8)) {
            return "webp";
        }
        return null;
    }

    /**
     * 获取当前用户发布的新闻列表（分页）
     */
    public Result.PageResult<News> getUserNews(Integer userId, int page, int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        long total = newsMapper.countByUser(userId);
        List<News> records = total == 0 ? List.of()
                : newsMapper.selectByUserPage(userId, (page - 1) * pageSize, pageSize);
        return new Result.PageResult<>(records, total, page, pageSize);
    }

    private User findById(Integer userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private UserResponse buildUserResponse(User user) {
        return UserResponse.builder()
                .uid(user.getUid())
                .displayId(user.getDisplayId())
                .username(user.getUsername())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
