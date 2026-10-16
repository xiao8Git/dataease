package io.dataease.localaccess.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LocalAccessMvcConfig implements WebMvcConfigurer {
    private final LocalAccessInterceptor interceptor;
    public LocalAccessMvcConfig(LocalAccessInterceptor interceptor) { this.interceptor = interceptor; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).order(-100).addPathPatterns("/**");
    }
}
