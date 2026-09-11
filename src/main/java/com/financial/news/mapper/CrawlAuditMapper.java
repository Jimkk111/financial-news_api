package com.financial.news.mapper;

import com.financial.news.entity.CrawlAudit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CrawlAuditMapper {

    int insert(CrawlAudit crawlAudit);
}
