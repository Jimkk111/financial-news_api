package com.financial.news.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 浏览历史实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("history")
public class History {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer userId;

    private Integer newsId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime viewedAt;
}
