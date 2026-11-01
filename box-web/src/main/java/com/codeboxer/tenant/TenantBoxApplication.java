package com.codeboxer.tenant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * @Author: 王志伟
 * @Date: 2026/11/1 15:33
 * @Description:  TenantBox 启动类
 **/
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class TenantBoxApplication {
    public static void main(String[] args) {
        SpringApplication.run(TenantBoxApplication.class, args);
        System.out.println("\n=================================================");
        System.out.println("🚀 租户盒子 (TenantBox-Starter) 启动成功！");
        System.out.println("👉 首页访问地址: http://localhost:8080");
        System.out.println("=================================================\n");
    }
}

