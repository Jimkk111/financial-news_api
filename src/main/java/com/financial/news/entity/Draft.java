package com.financial.news.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 草稿实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("drafts")
public class Draft {

    /** 草稿 ID，格式 draft-xxxxxxxx */
    @TableId
    private String id;

    /** 用户 ID */
    private Integer userId;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 封面图 URL */
    private String coverImage;

    /** 分类 ID */
    private Integer categoryId;

    /** 状态：draft / published */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
