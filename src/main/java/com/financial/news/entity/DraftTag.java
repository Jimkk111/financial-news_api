package com.financial.news.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 草稿-标签关联实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("draft_tags")
public class DraftTag {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 草稿 ID */
    private String draftId;

    /** 标签 ID */
    private Integer tagId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
