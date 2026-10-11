package com.financial.news.service;

import com.financial.news.entity.News;
import com.financial.news.entity.Tag;
import com.financial.news.mapper.NewsMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewsTagsTest {
    @Test
    void listReturnsAssociatedTagsAndEmptyArrayForUntaggedNews() {
        NewsMapper mapper = mock(NewsMapper.class);
        News tagged = News.builder().id(1).title("Tagged").build();
        News untagged = News.builder().id(2).title("Untagged").build();
        Tag tag = new Tag();
        tag.setId(9);
        tag.setName("央行政策");
        when(mapper.countListPage(null)).thenReturn(2L);
        when(mapper.selectListPage(null, false, 0, 20)).thenReturn(List.of(tagged, untagged));
        when(mapper.selectTagsByNewsIds(List.of(1, 2)))
                .thenReturn(List.of(News.builder().id(1).tags(List.of(tag)).build()));

        NewsService service = new NewsService(mapper, null, null, null, null);
        var result = service.listNews(null, "newest", 1, 20);

        assertEquals(List.of(tag), result.getRecords().get(0).getTags());
        assertEquals(List.of(), result.getRecords().get(1).getTags());
        assertEquals(2L, result.getTotal());
        verify(mapper, times(1)).selectTagsByNewsIds(List.of(1, 2));
    }

    @Test
    void searchAlsoReturnsTags() {
        NewsMapper mapper = mock(NewsMapper.class);
        Tag tag = new Tag();
        tag.setId(17);
        tag.setName("经济数据");
        when(mapper.countSearch("经济")).thenReturn(1L);
        when(mapper.searchPage("经济", 0, 20)).thenReturn(List.of(News.builder().id(3).build()));
        when(mapper.selectTagsByNewsIds(List.of(3)))
                .thenReturn(List.of(News.builder().id(3).tags(List.of(tag)).build()));
        NewsService service = new NewsService(mapper, null, null, null, null);
        assertEquals(List.of(tag), service.searchNews("经济", 1, 20).getRecords().get(0).getTags());
    }

    @Test
    void emptyPagesDoNotQueryTags() {
        NewsMapper mapper = mock(NewsMapper.class);
        NewsService service = new NewsService(mapper, null, null, null, null);
        assertTrue(service.listNews(null, "newest", 1, 20).getRecords().isEmpty());
        assertTrue(service.searchNews("missing", 1, 20).getRecords().isEmpty());
        verify(mapper, never()).selectTagsByNewsIds(anyList());
    }

    @Test
    void mapperParsesAndBindsPageIds() throws Exception {
        Configuration config = new Configuration();
        try (var input = Resources.getResourceAsStream("mapper/NewsMapper.xml")) {
            new XMLMapperBuilder(input, config, "mapper/NewsMapper.xml", config.getSqlFragments()).parse();
        }
        var statement = config.getMappedStatement("com.financial.news.mapper.NewsMapper.selectTagsByNewsIds");
        var sql = statement.getBoundSql(Map.of("ids", List.of(1, 2)));
        assertEquals(2, sql.getParameterMappings().size());
        assertTrue(sql.getSql().contains("INNER JOIN tags"));
        assertTrue(statement.getResultMaps().get(0).getResultMappings().stream()
                .anyMatch(mapping -> "tags".equals(mapping.getProperty()) && mapping.getNestedResultMapId() != null));
    }
}