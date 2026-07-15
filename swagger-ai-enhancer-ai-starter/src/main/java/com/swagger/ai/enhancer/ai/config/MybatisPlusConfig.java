package com.swagger.ai.enhancer.ai.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * ai-starter MyBatis-Plus 自动配置。
 * <p>
 * 仅当显式开启 RAG 功能（{@code swagger-ai-enhancer.ai.rag.enabled=true}）时才装配
 * DataSource、SqlSessionFactory 及事务相关 Bean；未开启时不会触发数据库连接，
 * 宿主应用可在纯 API 补全模式下零数据库启动。
 * <p>
 * 数据库连接信息允许通过 application.yml 或环境变量覆盖：
 * <ul>
 *   <li>{@code spring.datasource.url} / {@code SPRING_DATASOURCE_URL}</li>
 *   <li>{@code spring.datasource.username} / {@code SPRING_DATASOURCE_USERNAME}</li>
 *   <li>{@code spring.datasource.password} / {@code SPRING_DATASOURCE_PASSWORD}</li>
 *   <li>{@code spring.datasource.driver-class-name}</li>
 *   <li>{@code MYSQL_PASSWORD} 也可用于注入密码（优先级低于 spring.datasource.password）</li>
 * </ul>
 * 若应用已显式提供 DataSource / SqlSessionFactory，本配置将被自动跳过。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ MybatisSqlSessionFactoryBean.class })
@ConditionalOnProperty(
        name = "swagger-ai-enhancer.ai.rag.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class MybatisPlusConfig {

    private static final Logger log = LoggerFactory.getLogger(MybatisPlusConfig.class);

    // 硬编码默认值，避免强依赖 application.yml 中的 spring.datasource.* 配置
    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/swagger_ai_enhancer"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DEFAULT_USERNAME = "root";
    private static final String DEFAULT_PASSWORD = "root";
    private static final String DEFAULT_DRIVER = "com.mysql.cj.jdbc.Driver";

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource dataSource(Environment env) {
        // 允许外部覆盖：优先 application.yml / 环境变量，其次使用硬编码默认值
        String url = env.getProperty("spring.datasource.url", DEFAULT_URL);
        String username = env.getProperty("spring.datasource.username", DEFAULT_USERNAME);
        // 密码允许通过环境变量 MYSQL_PASSWORD 或 spring.datasource.password 注入
        String password = env.getProperty("spring.datasource.password",
                env.getProperty("MYSQL_PASSWORD", DEFAULT_PASSWORD));
        String driver = env.getProperty("spring.datasource.driver-class-name", DEFAULT_DRIVER);

        log.info("[ai-starter] 默认 DataSource: url={}, username={}, driver={}", url, username, driver);

        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driver)
                .type(com.zaxxer.hikari.HikariDataSource.class)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(SqlSessionFactory.class)
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        Resource[] mapperLocations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/**/*.xml");
        factory.setMapperLocations(mapperLocations);

        factory.setTypeAliasesPackage("com.swagger.ai.enhancer.ai.entity");

        // MyBatis-Plus 核心配置：下划线 -> 驼峰
        MybatisConfiguration mpConfig = new MybatisConfiguration();
        mpConfig.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(mpConfig);

        // 全局配置：主键自增
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setIdType(IdType.AUTO);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(dbConfig);
        factory.setGlobalConfig(globalConfig);

        log.info("[ai-starter] MyBatis-Plus SqlSessionFactory 已注册（mapper xml 数量: {}）",
                mapperLocations.length);

        return factory.getObject();
    }
}
