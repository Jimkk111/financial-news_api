package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视频块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoBlock implements Block {

    @Override
    public String getType() { return "video"; }

    /** 视频 URL */
    private String url;

    /** 封面图 URL（可选） */
    private String poster;
}
