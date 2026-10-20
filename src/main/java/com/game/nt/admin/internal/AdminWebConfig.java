package com.game.nt.admin.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 后台路由注册：/admin/** 全部要求管理员 Cookie 令牌，登录页除外。
 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    public AdminWebConfig(AdminAuthInterceptor adminAuthInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/login");
    }
}
