package com.financial.news.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.financial.news.model.content.Block;
import com.financial.news.utils.BlockListTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
@TableName(value = "drafts", autoResultMap = true)
public class Draft {

    /** 草稿 ID，格式 draft-xxxxxxxx */
    @TableId
    private String id;

    /** 用户 ID */
    private Integer userId;

    /** 标题 */
    private String title;

    /** 内容（旧 HTML，过渡期保留） */
    private String content;

    /** 内容（块级 JSON，新契约；列表查询排除列后为 null 不输出） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @TableField(typeHandler = BlockListTypeHandler.class)
    private List<Block> contentJson;

    /** 封面图 URL */
    private String coverImage;

    /** 分类 ID */
    private Integer categoryId;

    /** 状态：draft / published */
    private String status;

    /** 标签 ID 列表（由 draft_tags 关联表维护，不映射数据库字段） */
    @TableField(exist = false)
    private List<Integer> tags;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
