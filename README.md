# 📦 TenantBox-Starter

> 开箱即用的多租户 Java 后端底座 (PC端)。整合 Redis、线程池、租户级 RBAC 与定时任务。不是 SDK，是一个拿来就能写业务的起步包。

---

## 🏗️ 模块架构说明

本项目采用 **Maven 多模块** 架构，基于 **Spring Boot + Thymeleaf**（服务端渲染）。为了保持代码高内聚、低耦合，各模块职责严格划分如下：

| 模块名称 | 核心定位 | 职责说明 (重点) |
| :--- | :--- | :--- |
| **`tenant-box-common`** | 公共工具库 | 存放纯工具类、全局常量、自定义异常、枚举等。**不依赖任何本项目其他模块**。 |
| **`tenant-box-framework`** | 底层基建层 | 存放多租户拦截器、MyBatis-Plus 配置、Redis 配置、线程池、Spring Security 安全配置等。**不写具体业务**。 |
| **`tenant-box-system`** | 核心业务层 | 存放 RBAC 业务逻辑（用户、角色、权限、菜单）、实体类(Entity/DTO)、数据库交互(Mapper/Service)。**不写 Controller 和 HTML**。 |
| **`tenant-box-schedule`** | 定时任务模块 | 存放 Quartz 或 Spring Task 的调度配置与任务执行器。 |
| **`tenant-box-web`** | Web 控制层 & 启动器 | **项目的入口**。存放启动类、所有的 `Controller`、`templates` (所有 HTML 页面)、`static` (CSS/JS)、`application.yml`。 |

---

## 🛠️ 模块依赖配置指南 (防报错必看)

因为使用了 Maven 多模块，各子模块的 `pom.xml` 必须正确配置 `<parent>` 和 `<dependency>`。如果报红找不到类，请核对以下配置。

**所有子模块的统一父级配置 (放在各子模块 pom.xml 的 `<project>` 标签内最上方)：**
```xml
<parent>
    <groupId>com.codeboxer</groupId>
    <artifactId>tenant-box-starter</artifactId>
    <version>1.0.0</version>
    <relativePath>../pom.xml</relativePath>
</parent>
```
各模块之间的依赖关系（金字塔结构）：
1. tenant-box-framework/pom.xml 需要依赖 common：

```xml
<dependency>
    <groupId>com.codeboxer</groupId>
    <artifactId>tenant-box-common</artifactId>
    <version>1.0.0</version>
</dependency>
```
2. tenant-box-system/pom.xml 需要依赖 framework：

```xml
<dependency>
    <groupId>com.codeboxer</groupId>
    <artifactId>tenant-box-framework</artifactId>
    <version>1.0.0</version>
</dependency>
```
3. tenant-box-schedule/pom.xml 需要依赖 system：

```xml
<dependency>
    <groupId>com.codeboxer</groupId>
    <artifactId>tenant-box-system</artifactId>
    <version>1.0.0</version>
</dependency>
```
4. tenant-box-web/pom.xml (顶层，需要依赖所有业务模块 + Web 依赖)：

```xml
<dependencies>
    <!-- 依赖 system 和 schedule -->
    <dependency>
        <groupId>com.codeboxer</groupId>
        <artifactId>tenant-box-system</artifactId>
        <version>1.0.0</version>
    </dependency>
    <dependency>
        <groupId>com.codeboxer</groupId>
        <artifactId>tenant-box-schedule</artifactId>
        <version>1.0.0</version>
    </dependency>

    <!-- Web 与模板引擎依赖 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-thymeleaf</artifactId>
    </dependency>
</dependencies>
```
## 🚀 快速开始 (Quick Start)
克隆项目到本地。

使用 IDEA 打开根目录，等待 Maven 依赖下载完毕。

找到 tenant-box-web 模块下的 com.codeboxer.tenant.TenantBoxApplication 启动类。

运行项目，控制台打印 "=== 租户盒子 (TenantBox) 启动成功！ ==="。

浏览器访问：http://localhost:8080。

##  📄 开源协议 (License)
MIT License
