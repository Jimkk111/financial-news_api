package com.financial.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.request.DraftCreateRequest;
import com.financial.news.dto.request.DraftUpdateRequest;
import com.financial.news.entity.Draft;
import com.financial.news.entity.News;
import com.financial.news.mapper.DraftMapper;
import com.financial.news.mapper.NewsMapper;
import com.financial.news.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 草稿服务
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftService extends ServiceImpl<DraftMapper, Draft> {

    private final DraftMapper draftMapper;
    private final NewsMapper newsMapper;

    /**
     * 获取当前用户的草稿列表（按更新时间倒序，只返回 status='draft'）
     */
    public List<Draft> listDrafts(Integer userId) {
        return draftMapper.selectList(new LambdaQueryWrapper<Draft>()
                .eq(Draft::getUserId, userId)
                .eq(Draft::getStatus, "draft")
                .orderByDesc(Draft::getUpdatedAt));
    }

    /**
     * 创建草稿
     */
    public Draft createDraft(Integer userId, DraftCreateRequest request) {
        Draft draft = Draft.builder()
                .id(IdGenerator.generateDraftId())
                .userId(userId)
                .title(request.getTitle())
                .content(request.getContent())
                .coverImage(request.getCoverImage())
                .categoryId(request.getCategoryId())
                .status("draft")
                .build();
        draftMapper.insert(draft);
        return draft;
    }

    /**
     * 获取草稿详情（校验所属权）
     */
    public Draft getDraft(String draftId, Integer userId) {
        Draft draft = draftMapper.selectById(draftId);
        if (draft == null) throw new BusinessException(ErrorCode.DRAFT_NOT_FOUND);
        if (!draft.getUserId().equals(userId)) throw new BusinessException(ErrorCode.DRAFT_NOT_OWNER);
        return draft;
    }

    /**
     * 更新草稿
     */
    @Transactional
    public Draft updateDraft(String draftId, Integer userId, DraftUpdateRequest request) {
        Draft draft = getDraft(draftId, userId);
        if ("published".equals(draft.getStatus())) {
            throw new BusinessException(ErrorCode.DRAFT_ALREADY_PUBLISHED);
        }
        if (request.getTitle() != null) draft.setTitle(request.getTitle());
        if (request.getContent() != null) draft.setContent(request.getContent());
        if (request.getCoverImage() != null) draft.setCoverImage(request.getCoverImage());
        if (request.getCategoryId() != null) draft.setCategoryId(request.getCategoryId());
        draftMapper.updateById(draft);
        return draft;
    }

    /**
     * 删除草稿
     */
    public void deleteDraft(String draftId, Integer userId) {
        Draft draft = getDraft(draftId, userId);
        draftMapper.deleteById(draft.getId());
    }

    /**
     * 发布草稿（转为新闻）
     */
    @Transactional
    public News publishDraft(String draftId, Integer userId) {
        Draft draft = getDraft(draftId, userId);
        if ("published".equals(draft.getStatus())) {
            throw new BusinessException(ErrorCode.DRAFT_ALREADY_PUBLISHED);
        }
        if (draft.getTitle() == null || draft.getTitle().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿标题不能为空");
        }

        News news = News.builder()
                .title(draft.getTitle())
                .content(draft.getContent())
                .summary(draft.getContent() != null && draft.getContent().length() > 200
                        ? draft.getContent().substring(0, 200) : draft.getContent())
                .hasImage(draft.getCoverImage() != null && !draft.getCoverImage().isBlank())
                .imageUrl(draft.getCoverImage())
                .categoryId(draft.getCategoryId())
                .userId(userId)
                .views(0)
                .publishTime(LocalDateTime.now())
                .source(null) // source 通过用户信息获取
                .build();
        newsMapper.insert(news);

        draft.setStatus("published");
        draftMapper.updateById(draft);

        return news;
    }
}
