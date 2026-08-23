package com.financial.news.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.news.entity.DraftTag;
import org.apache.ibatis.annotations.Mapper;

/**
 * 草稿-标签关联 Mapper
 */
@Mapper
public interface DraftTagMapper extends BaseMapper<DraftTag> {
}
