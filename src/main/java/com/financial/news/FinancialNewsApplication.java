package com.financial.news;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 财经新闻 API 服务 - 启动类
 *
 * @author financial-news
 * @since 1.0.0
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.financial.news.mapper")
public class FinancialNewsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinancialNewsApplication.class, args);
    }
}
