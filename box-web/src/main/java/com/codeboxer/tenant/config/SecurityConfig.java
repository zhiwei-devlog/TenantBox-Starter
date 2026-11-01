package com.codeboxer.tenant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * @Author: 王志伟
 * @Date: 2026/11/1 15:41
 * @Description:
 **/
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 先关闭 CSRF 校验（前端发请求方便点）
                .csrf(AbstractHttpConfigurer::disable)
                // 放行所有请求（临时方案，后续做鉴权再改）
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                // 关闭默认表单登录和 HTTP Basic，避免拦截 GET 请求
                .formLogin(AbstractHttpConfigurer::disable)
                // 显式禁用 HTTP Basic 认证
                .httpBasic(AbstractHttpConfigurer::disable)
                // 禁用匿名认证，确保请求能正常到达控制器
                .anonymous(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
