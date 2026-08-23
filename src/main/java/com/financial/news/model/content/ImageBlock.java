package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageBlock implements Block {

    @Override
    public String getType() { return "image"; }

    /** 图片 URL */
    private String url;

    /** 图片说明文字 */
    private String caption;
}
