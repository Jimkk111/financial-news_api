package com.financial.news.mapper;

import com.financial.news.entity.DraftTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 草稿-标签关联 Mapper
 */
@Mapper
public interface DraftTagMapper {

    int insert(DraftTag draftTag);

    List<DraftTag> selectByDraftId(@Param("draftId") String draftId);

    List<DraftTag> selectByDraftIds(@Param("draftIds") List<String> draftIds);

    int deleteByDraftId(@Param("draftId") String draftId);
}
