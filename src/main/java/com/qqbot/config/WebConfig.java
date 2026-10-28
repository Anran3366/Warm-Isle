package com.qqbot.config;

import com.qqbot.auth.AuthInterceptor;
import com.qqbot.bot.BotContextFilter;
import org.springframework.context.annotation.Configuration;
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
}
