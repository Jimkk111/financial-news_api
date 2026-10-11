package com.financial.news.service;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.entity.Draft;
import com.financial.news.entity.News;
import com.financial.news.mapper.DraftMapper;
import com.financial.news.mapper.NewsMapper;
import com.financial.news.model.content.*;
import com.financial.news.utils.ContentCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/** 私有媒体的资源授权与入库规范化，不接受任意路径的匿名签名。 */
@Service
@RequiredArgsConstructor
public class MediaAccessService {
    private final MediaService mediaService;
    private final NewsMapper newsMapper;
    private final DraftMapper draftMapper;

    public String refresh(String url, Integer newsId, String draftId, Integer userId) {
        if (mediaService.objectKey(url) == null) throw new BusinessException(ErrorCode.BAD_REQUEST);
        boolean allowed = false;
        if (newsId != null) {
            News news = newsMapper.selectById(newsId);
            if (news == null) throw new BusinessException(ErrorCode.NEWS_NOT_FOUND);
            allowed = references(news.getImageUrl(), blocks(news.getContentJson(), news.getContent())).contains(mediaService.stableUrl(url));
        } else if (draftId != null) {
            if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
            Draft draft = draftMapper.selectById(draftId);
            if (draft == null) throw new BusinessException(ErrorCode.DRAFT_NOT_FOUND);
            if (!userId.equals(draft.getUserId())) throw new BusinessException(ErrorCode.DRAFT_NOT_OWNER);
            allowed = references(draft.getCoverImage(), blocks(draft.getContentJson(), draft.getContent())).contains(mediaService.stableUrl(url));
        } else {
            // 刚上传、尚未保存到草稿的预览，只允许上传者续签。
            allowed = mediaService.ownedBy(url, userId);
        }
        if (!allowed) throw new BusinessException(ErrorCode.FORBIDDEN);
        return mediaService.accessUrl(url);
    }

    public void prepareForSave(Draft draft, Integer userId, Set<String> existingReferences) {
        UnaryOperator<String> normalize = url -> {
            if (mediaService.objectKey(url) == null) return url;
            String stable = mediaService.stableUrl(url);
            if (!mediaService.ownedBy(url, userId) && !existingReferences.contains(stable)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "不能引用其他用户的私有媒体，请重新上传文件");
            }
            return stable;
        };
        draft.setCoverImage(normalize.apply(draft.getCoverImage()));
        if (draft.getContentJson() != null) {
            // 深拷贝，避免响应/请求对象中的签名地址污染其他引用。
            List<Block> copy = ContentCodec.fromJson(ContentCodec.toJson(draft.getContentJson()));
            mapBlocks(copy, normalize);
            draft.setContentJson(copy);
            draft.setContent(ContentCodec.blocksToHtml(copy));
        }
    }

    public Set<String> references(Draft draft) {
        return references(draft.getCoverImage(), blocks(draft.getContentJson(), draft.getContent()));
    }

    private List<Block> blocks(List<Block> content, String html) {
        return content != null ? content : html == null ? List.of() : ContentCodec.fromHtml(html);
    }

    private Set<String> references(String cover, List<Block> blocks) {
        Set<String> references = new HashSet<>();
        UnaryOperator<String> collect = url -> {
            if (url != null) references.add(mediaService.stableUrl(url));
            return url;
        };
        collect.apply(cover);
        mapBlocks(blocks, collect);
        return references;
    }

    private void mapBlocks(List<Block> blocks, UnaryOperator<String> transform) {
        if (blocks == null) return;
        for (Block block : blocks) {
            if (block instanceof ImageBlock image) image.setSrc(transform.apply(image.getSrc()));
            else if (block instanceof VideoBlock video) {
                video.setSrc(transform.apply(video.getSrc()));
                video.setPoster(transform.apply(video.getPoster()));
            } else if (block instanceof BlockquoteBlock quote) mapBlocks(quote.getChildren(), transform);
        }
    }
}
