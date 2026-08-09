package com.financial.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.request.UpdateUserRequest;
import com.financial.news.dto.response.UserResponse;
import com.financial.news.entity.News;
import com.financial.news.entity.User;
import com.financial.news.mapper.NewsMapper;
import com.financial.news.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
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
public class UserService extends ServiceImpl<UserMapper, User> {

    private final UserMapper userMapper;
    private final NewsMapper newsMapper;

    @Value("${upload.dir:uploads}")
    private String uploadDir;

    @Value("${upload.url-prefix:/uploads}")
    private String uploadUrlPrefix;

    @Value("${upload.avatar-max-size:2097152}")
    private long avatarMaxSize;

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
            if (userMapper.exists(new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()))) {
                throw new BusinessException(ErrorCode.USERNAME_EXISTS);
            }
            user.setUsername(request.getUsername());
            updated = true;
        }
        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            if (userMapper.exists(new LambdaQueryWrapper<User>().eq(User::getEmail, request.getEmail()))) {
                throw new BusinessException(ErrorCode.EMAIL_EXISTS);
            }
            user.setEmail(request.getEmail());
            updated = true;
        }

        if (!updated) {
            throw new BusinessException(ErrorCode.NO_UPDATE_DATA);
        }

        userMapper.updateById(user);
        return buildUserResponse(user);
    }

    /**
     * 上传头像
     */
    @Transactional
    public String uploadAvatar(Integer userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_FILE);
        }
        if (file.getSize() > avatarMaxSize) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.matches("image/(jpeg|png|gif|webp)")) {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
        }

        try {
            LocalDate now = LocalDate.now();
            String relativePath = String.format("avatars/%d/%02d/%d_%s.%s",
                    now.getYear(), now.getMonthValue(),
                    System.currentTimeMillis(),
                    UUID.randomUUID().toString().substring(0, 8),
                    contentType.substring(contentType.indexOf('/') + 1));

            Path targetPath = Paths.get(uploadDir, relativePath);
            Files.createDirectories(targetPath.getParent());
            file.transferTo(targetPath.toFile());

            String avatarUrl = uploadUrlPrefix + "/" + relativePath.replace("\\", "/");
            User user = findById(userId);
            user.setAvatar(avatarUrl);
            userMapper.updateById(user);

            return avatarUrl;
        } catch (IOException e) {
            log.error("头像上传失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件上传失败");
        }
    }

    /**
     * 获取当前用户发布的新闻列表（分页）
     */
    public Page<News> getUserNews(Integer userId, int page, int pageSize) {
        Page<News> pageParam = new Page<>(page, Math.min(pageSize, 50));
        return newsMapper.selectPage(pageParam,
                new LambdaQueryWrapper<News>()
                        .eq(News::getUserId, userId)
                        .orderByDesc(News::getCreatedAt));
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
