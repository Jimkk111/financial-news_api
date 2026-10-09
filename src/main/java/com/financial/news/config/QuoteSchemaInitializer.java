package com.financial.news.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 行情模块数据库初始化器。
 *
 * <p>Docker 的 init.sql 只会在数据卷首次创建时执行，存量数据库升级不会自动获得
 * 行情表。这里在启动同步任务触发前执行幂等迁移，保证本地、Docker 和生产环境的
 * 现有数据库都能安全升级。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class QuoteSchemaInitializer implements ApplicationRunner {

    private static final String MIGRATION = "db/migration/v800-quote.sql";

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSqlScriptEncoding("UTF-8");
        populator.setContinueOnError(false);
        populator.addScript(new ClassPathResource(MIGRATION));
        DatabasePopulatorUtils.execute(populator, dataSource);
        log.info("行情数据库结构已就绪: {}", MIGRATION);
    }
}
