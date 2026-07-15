package com.swagger.ai.enhancer.ui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * ui-starter 自动配置类。
 * 在 Spring Boot 启动时，通过 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 被加载。
 * 负责注册 {@link SwaggerAiScriptInjector}，在 swagger-initializer.js 末尾追加 swagger-ai-plugin.js 的引用。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SwaggerAiUiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SwaggerAiUiAutoConfiguration.class);

    public SwaggerAiUiAutoConfiguration() {
        log.info("ui-starter 自动配置已加载");
    }

    /**
     * 在 springdoc 已经生成 {@code swagger-initializer.js} 之后，再在其响应体末尾注入一行
     * {@code document.write('<script src=...></script>');}，从而在 Swagger UI 页面里
     * 懒加载 swagger-ai-plugin.js（及其内部动态注入的 CSS）。
     *
     * <p>用 {@link FilterRegistrationBean} 而非
     * {@link org.springframework.web.servlet.resource.ResourceTransformer}
     * 注册的原因是：springdoc 2.5.x 的资源处理链 {@code SwaggerIndexPageTransformer} 被封闭在
     * 其私有的 {@code SwaggerWebMvcConfigurer} 中，无法通过对外 API 扩展；Filter 方式可以保证
     * 在 springdoc 处理完之后再做一次文本拼接，不破坏 springdoc 原有的配置 URL、CSRF 等替换逻辑。
     */
    @Bean
    @ConditionalOnMissingBean(name = "swaggerAiScriptInjector")
    public FilterRegistrationBean<SwaggerAiScriptInjector> swaggerAiScriptInjector() {
        FilterRegistrationBean<SwaggerAiScriptInjector> registration =
                new FilterRegistrationBean<>(new SwaggerAiScriptInjector());
        // 仅匹配 swagger-ui 下 initializer 的请求 URL，其他资源直接放行
        registration.addUrlPatterns("/swagger-ui/swagger-initializer.js", "/webjars/swagger-ui/*/swagger-initializer.js");
        registration.setName("swaggerAiScriptInjector");
        // 放在资源过滤器之后，但在 springdoc 内部资源处理之后，以确保拿到的是 springdoc
        // 已经改写过的 initializer
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        log.info("[swagger-ai-enhancer] 已注册 swagger-initializer.js 注入过滤器，将在 initializer 末尾追加 swagger-ai-plugin.js 引用");
        return registration;
    }
}
