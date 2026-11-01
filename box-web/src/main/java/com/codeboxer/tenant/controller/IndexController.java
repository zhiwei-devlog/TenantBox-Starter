package com.codeboxer.tenant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * @Author: 王志伟
 * @Date: 2026/11/1 15:11
 * @Description:
 **/
@Controller
public class IndexController {
    @GetMapping("/")
    public String index(Model model) {
        // 给前端页面传参
        model.addAttribute("projectName", "TenantBox");
        model.addAttribute("desc", "开箱即用的多租户后端底座");
        // 页面返回必须以 / 开头
        return "/index";
    }
}
