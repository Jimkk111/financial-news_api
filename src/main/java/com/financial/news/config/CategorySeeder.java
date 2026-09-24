package com.financial.news.config;

import com.financial.news.entity.Category;
import com.financial.news.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分类种子补种器
 * <p>预置财经分类体系在应用启动时幂等补种，同时覆盖新建库与存量库（Docker 卷不重建的场景）。
 * 解决 categories 表零种子导致 AI 打标无分类可选、全部落入 3 个来源兜底分类的问题；
 * 清单与 db/init.sql 的 INSERT 保持一致，以本常量为单一事实源。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategorySeeder implements ApplicationRunner {

    /** 预置财经分类（顺序即展示顺序）；AI 打标在库为空时也以此作为候选 */
    public static final List<String> PRESET_CATEGORIES = List.of(
            "宏观经济", "货币政策", "A股", "港股", "美股", "全球市场",
            "公司动态", "产业经济", "基金理财", "债券", "外汇",
            "大宗商品", "房地产", "金融科技", "综合财经");

    private final CategoryMapper categoryMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Set<String> existing = categoryMapper.selectListAll().stream()
                    .map(Category::getName).collect(Collectors.toSet());
            List<String> missing = PRESET_CATEGORIES.stream()
                    .filter(name -> !existing.contains(name)).toList();
            for (String name : missing) {
                try {
                    categoryMapper.insert(Category.builder().name(name).build());
                } catch (DuplicateKeyException e) {
                    // 并发实例或人工已创建，忽略
                }
            }
            if (!missing.isEmpty()) {
                log.info("分类种子补种 {} 个: {}", missing.size(), missing);
            }
        } catch (Exception e) {
            // 数据库尚未就绪等问题不阻断启动，采集入库仍可按需新建
            log.warn("分类种子补种失败（不影响启动）: {}", e.getMessage());
        }
    }
}
