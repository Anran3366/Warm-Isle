package com.qqbot.config;

import com.qqbot.auth.AuthInterceptor;
import com.qqbot.bot.BotContextFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final BotContextFilter botContextFilter;

    public WebConfig(AuthInterceptor authInterceptor, BotContextFilter botContextFilter) {
        this.authInterceptor = authInterceptor;
        this.botContextFilter = botContextFilter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }

    /**
     * 官网（example.com / web.example.com）要读一个只读的聚合统计，跨域只放开这一个前缀，
     * 不放开整个 /api，避免后台接口被别的站点拿去用。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/public/**")
                .allowedOrigins("https://example.com", "https://web.example.com",
                        "http://localhost:8098", "http://127.0.0.1:8098")
                .allowedMethods("GET")
                .maxAge(3600);
    }
}
