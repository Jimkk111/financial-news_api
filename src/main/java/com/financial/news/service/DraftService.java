package com.financial.news.service;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.request.DraftCreateRequest;
import com.financial.news.dto.request.DraftUpdateRequest;
import com.financial.news.entity.Draft;
import com.financial.news.entity.DraftTag;
import com.financial.news.entity.News;
import com.financial.news.entity.NewsTag;
import com.financial.news.mapper.DraftMapper;
import com.financial.news.mapper.DraftTagMapper;
import com.financial.news.mapper.NewsMapper;
import com.financial.news.mapper.NewsTagMapper;
import com.financial.news.mapper.TagMapper;
import com.financial.news.model.content.Block;
import com.financial.news.utils.ContentCodec;
import com.financial.news.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 草稿服务
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftService {

    private final DraftMapper draftMapper;
    private final NewsMapper newsMapper;
    private final DraftTagMapper draftTagMapper;
    private final NewsTagMapper newsTagMapper;
    private final TagMapper tagMapper;

    /**
     * 获取当前用户的草稿列表（按更新时间倒序，只返回 status='draft'）
     */
    public List<Draft> listDrafts(Integer userId) {
        List<Draft> drafts = draftMapper.selectListByUser(userId);
        fillDraftTags(drafts);
        return drafts;
    }

    /**
     * 创建草稿（含标签关联）
     */
    @Transactional
    public Draft createDraft(Integer userId, DraftCreateRequest request) {
        Draft draft = Draft.builder()
                .id(IdGenerator.generateDraftId())
                .userId(userId)
                .title(request.getTitle())
                .content(request.getContent())
                .contentJson(resolveContentJson(request.getContentJson(), request.getContent()))
                .coverImage(request.getCoverImage())
                .categoryId(request.getCategoryId())
                .status("draft")
                .build();
        draftMapper.insert(draft);
        saveDraftTags(draft.getId(), request.getTags());
        draft.setTags(request.getTags() == null ? List.of() : request.getTags());
        return draft;
    }

    /**
     * 获取草稿详情（校验所属权）
     */
    public Draft getDraft(String draftId, Integer userId) {
        Draft draft = draftMapper.selectById(draftId);
        if (draft == null) throw new BusinessException(ErrorCode.DRAFT_NOT_FOUND);
        if (!draft.getUserId().equals(userId)) throw new BusinessException(ErrorCode.DRAFT_NOT_OWNER);
        draft.setTags(getDraftTags(draftId));
        // 存量草稿未迁移时，响应中实时从 HTML 转换（不落库，由迁移任务负责）
        if (draft.getContentJson() == null && draft.getContent() != null) {
            draft.setContentJson(ContentCodec.fromHtml(draft.getContent()));
        }
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
        if (request.getContentJson() != null) {
            draft.setContentJson(ContentCodec.normalize(request.getContentJson()));
        }
        if (request.getCoverImage() != null) draft.setCoverImage(request.getCoverImage());
        if (request.getCategoryId() != null) draft.setCategoryId(request.getCategoryId());
        if (request.getTags() != null) {
            replaceDraftTags(draftId, request.getTags());
            draft.setTags(request.getTags());
        }
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

        List<Block> contentJson = draft.getContentJson() != null
                ? draft.getContentJson()
                : ContentCodec.fromHtml(draft.getContent());

        News news = News.builder()
                .title(draft.getTitle())
                .content(draft.getContent())
                .contentJson(contentJson)
                .summary(buildSummary(contentJson, draft.getContent()))
                .hasImage(draft.getCoverImage() != null && !draft.getCoverImage().isBlank())
                .imageUrl(draft.getCoverImage())
                .categoryId(draft.getCategoryId())
                .userId(userId)
                .views(0)
                .publishTime(LocalDateTime.now())
                .source(null) // source 通过用户信息获取
                .build();
        newsMapper.insert(news);

        // 草稿标签关联到新闻（news_tags）
        List<Integer> tagIds = getDraftTags(draft.getId());
        for (Integer tagId : tagIds) {
            newsTagMapper.insert(NewsTag.builder().newsId(news.getId()).tagId(tagId).build());
        }

        draft.setStatus("published");
        draftMapper.updateById(draft);

        return news;
    }

    /**
     * 校验标签 ID 是否全部存在
     */
    private void validateTagIds(List<Integer> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return;
        List<Integer> distinctIds = tagIds.stream().distinct().toList();
        if (tagMapper.selectByIds(distinctIds).size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "包含不存在的标签");
        }
    }

    /**
     * 保存草稿标签关联
     */
    private void saveDraftTags(String draftId, List<Integer> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return;
        validateTagIds(tagIds);
        for (Integer tagId : tagIds.stream().distinct().toList()) {
            draftTagMapper.insert(DraftTag.builder().draftId(draftId).tagId(tagId).build());
        }
    }

    /**
     * 全量替换草稿标签关联
     */
    private void replaceDraftTags(String draftId, List<Integer> tagIds) {
        draftTagMapper.deleteByDraftId(draftId);
        saveDraftTags(draftId, tagIds);
    }

    /**
     * 获取草稿关联的标签 ID 列表
     */
    private List<Integer> getDraftTags(String draftId) {
        return draftTagMapper.selectByDraftId(draftId)
                .stream().map(DraftTag::getTagId).toList();
    }

    /**
     * 解析写入用的块级 JSON：contentJson 优先，旧 HTML 提交时服务端转换兼容
     */
    private List<Block> resolveContentJson(List<Block> contentJson, String content) {
        if (contentJson != null) return ContentCodec.normalize(contentJson);
        if (content != null && !content.isBlank()) return ContentCodec.fromHtml(content);
        return null;
    }

    /**
     * 生成新闻摘要：优先从块级 JSON 提取纯文本，回退旧 HTML 截断
     */
    private String buildSummary(List<Block> contentJson, String fallbackContent) {
        if (contentJson != null && !contentJson.isEmpty()) {
            String text = ContentCodec.toPlainText(contentJson);
            return text.length() > 200 ? text.substring(0, 200) : text;
        }
        if (fallbackContent != null) {
            return fallbackContent.length() > 200 ? fallbackContent.substring(0, 200) : fallbackContent;
        }
        return null;
    }

    /**
     * 批量填充草稿列表的标签（避免 N+1 查询）
     */
    private void fillDraftTags(List<Draft> drafts) {
        if (drafts.isEmpty()) return;
        List<String> draftIds = drafts.stream().map(Draft::getId).toList();
        List<DraftTag> relations = draftTagMapper.selectByDraftIds(draftIds);
        Map<String, List<Integer>> tagMap = relations.stream().collect(Collectors.groupingBy(
                DraftTag::getDraftId, Collectors.mapping(DraftTag::getTagId, Collectors.toList())));
        drafts.forEach(d -> d.setTags(tagMap.getOrDefault(d.getId(), List.of())));
    }
}
