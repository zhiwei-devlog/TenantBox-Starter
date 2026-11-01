# SaaS 多租户权限系统技术文档

> **技术栈**：Spring Boot 3.x + Thymeleaf + MyBatis + Spring Security 6.x + MySQL 8 + Redis  
> **目标**：一套代码服务多租户；租户间硬隔离；平台超管可跨租户；租户内数据分级可见；按钮级功能权限控制。  
> **文档性质**：可直接交付执行 agent 落地的技术设计文档。

---

## 目录

1. [需求与设计原则](#1-需求与设计原则)
2. [系统架构与请求链路](#2-系统架构与请求链路)
3. [数据库设计](#3-数据库设计)
4. [认证与权限加载](#4-认证与权限加载)
5. [三层过滤实现（租户/数据权限/功能权限）](#5-三层过滤实现)
6. [Thymeleaf 页面控制](#6-thymeleaf-页面控制)
7. [后端接口鉴权](#7-后端接口鉴权)
8. [跨租户引用校验](#8-跨租户引用校验跨租户引用校验service-层必做)
9. [归属字段语义与调岗处理](#9-归属字段语义与调岗处理)
10. [缓存规范](#10-缓存规范)
11. [索引与性能](#11-索引与性能)
12. [超管跨租户上下文设计](#12-超管跨租户上下文设计)
13. [实施顺序](#13-实施顺序)
14. [验收清单](#14-验收清单)
15. [明确不做/暂缓事项](#15-明确做暂缓事项)
16. [风险提示](#16-风险提示实现时重点盯)

---

## 1. 需求与设计原则

### 1.1 三个正交权限维度

| 维度 | 过滤字段 | 作用 | 谁能绕过 |
|------|----------|------|----------|
| 多租户隔离 | `tenant_id` | 租户之间绝对不可见 | 仅平台超管（平台端） |
| 数据权限 | `dept_id` / `owner_id` | 租户内分级可见（本部门/仅本人等） | 租户管理员（放宽到本租户全部） |
| 功能权限 | `perm_code` | 菜单可见性 + 按钮显隐 + 接口鉴权 | 平台超管（全有） |

### 1.2 不可违背的设计原则

1. **`tenant_id` 是硬边界**：普通用户（含租户管理员）的 SQL 必须强制追加 `tenant_id = ?`，由 MyBatis 拦截器统一注入，业务代码不得手动拼。

2. **超管穿透靠"跳过追加"**：用独立字段 `is_super_admin` 识别超管，不得用 `data_scope=1` 冒充，否则租户管理员会穿透到其他租户。

3. **`sys_permission` 全局共享**：不加 `tenant_id`，所有租户跑同一套功能，`perm_code` 全局唯一，仅平台超管可维护。

4. **`sys_user`、`sys_role`、`sys_dept` 及所有业务表按租户隔离**：加 `tenant_id`。

5. **前端藏按钮只是体验，安全永远靠后端**：写操作（update/delete by id）必须过租户 + 数据权限校验，不能只过滤 list。

6. **缓存 key 必须带 `tenant_id`**：否则跨租户串数据。

7. **业务归属字段是快照**：`dept_id` / `owner_id` 在业务创建时写入，用户调岗时不批量更新。

---

## 2. 系统架构与请求链路

### 2.1 请求过滤链（顺序不可颠倒）

```
请求
  → 第1关 Spring Security 认证（登录态、当前用户、租户上下文）
  → 第2关 功能权限（URL 规则 + @PreAuthorize hasAuthority）
  → 第3关 租户拦截器（tenant_id = 当前租户，硬边界）
  → 第4关 数据权限拦截器（data_scope 条件，租户内收窄）
  → 业务 SQL 执行
```

> **关键约束**：第 3 关必须先于第 4 关；两个拦截器均覆盖 select / update / delete。

### 2.2 最终 SQL 形态

```sql
SELECT *
FROM   biz_order
WHERE  tenant_id = :currentTenant          -- ① 租户拦截器（超管跳过）
  AND  (                                    -- ② 数据权限拦截器（超管跳过）
    dept_id IN (:本部门及子孙)
    OR owner_id = :currentUserId
  )
  AND  status = :queryStatus                -- ③ 业务查询条件
  AND  create_time >= :startTime
ORDER BY create_time DESC;
```

---

## 3. 数据库设计

### 3.1 租户表 sys_tenant

```sql
CREATE TABLE sys_tenant (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '租户ID',
    tenant_code VARCHAR(50)  NOT NULL UNIQUE COMMENT '租户编码',
    tenant_name VARCHAR(100) NOT NULL COMMENT '租户名称',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    expire_time DATETIME     COMMENT '到期时间',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '租户表';
```

### 3.2 用户表 sys_user

```sql
CREATE TABLE sys_user (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    tenant_id      BIGINT       NOT NULL COMMENT '租户ID，超管为0',
    is_super_admin TINYINT      NOT NULL DEFAULT 0 COMMENT '是否平台超管：1是 0否',
    username       VARCHAR(50)  NOT NULL COMMENT '登录名',
    password       VARCHAR(100) NOT NULL COMMENT 'BCrypt加密',
    nickname       VARCHAR(50)  COMMENT '昵称',
    dept_id        BIGINT       COMMENT '当前所属部门ID（会随调岗变化）',
    email          VARCHAR(100) COMMENT '邮箱',
    phone          VARCHAR(20)  COMMENT '手机号',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_tenant_username (tenant_id, username, deleted),
    KEY idx_tenant_dept (tenant_id, dept_id)
) COMMENT '用户表';
```

> **注意**：唯一索引带 `deleted` 为规避逻辑删除冲突方案之一；落地时可改为"删除时改写 username 加时间戳后缀"，二选一需在实现前定死。

### 3.3 角色表 sys_role

```sql
CREATE TABLE sys_role (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '角色ID',
    tenant_id   BIGINT       NOT NULL COMMENT '租户ID',
    role_code   VARCHAR(50)  NOT NULL COMMENT '角色编码，如 ADMIN、EDITOR',
    role_name   VARCHAR(50)  NOT NULL COMMENT '角色名称',
    data_scope  TINYINT      NOT NULL DEFAULT 1 COMMENT '租户内数据范围：1本租户全部 2本部门及以下 3本部门 4仅本人 5自定义',
    parent_id   BIGINT       COMMENT '预留：角色继承用，暂不实现',
    description VARCHAR(200) COMMENT '描述',
    sort        INT          NOT NULL DEFAULT 0,
    status      TINYINT      NOT NULL DEFAULT 1,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_tenant_role_code (tenant_id, role_code, deleted)
) COMMENT '角色表';
```

### 3.4 部门表 sys_dept

```sql
CREATE TABLE sys_dept (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL COMMENT '租户ID',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父部门ID，0为顶级',
    dept_name   VARCHAR(50)  NOT NULL,
    ancestors   VARCHAR(500) COMMENT '祖级路径，如 0,1,5，用于前缀匹配查子孙',
    sort        INT          NOT NULL DEFAULT 0,
    status      TINYINT      NOT NULL DEFAULT 1,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    KEY idx_tenant_parent (tenant_id, parent_id),
    KEY idx_tenant_ancestors (tenant_id, ancestors)
) COMMENT '部门表（每租户独立树）';
```

### 3.5 权限表 sys_permission（全局共享，不加 tenant_id）

```sql
CREATE TABLE sys_permission (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父级ID，0为顶级',
    perm_code   VARCHAR(100) COMMENT '权限码，如 user:add；菜单可空',
    perm_name   VARCHAR(50)  NOT NULL COMMENT '名称',
    type        TINYINT      NOT NULL COMMENT '1目录 2菜单 3按钮',
    path        VARCHAR(200) COMMENT '路由地址，菜单/目录用',
    icon        VARCHAR(50)  COMMENT '图标',
    sort        INT          NOT NULL DEFAULT 0,
    visible     TINYINT      NOT NULL DEFAULT 1 COMMENT '1显示 0隐藏',
    status      TINYINT      NOT NULL DEFAULT 1,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_perm_code (perm_code)
) COMMENT '权限/菜单表，全局共享，仅平台超管可维护';
```

### 3.6 关联表

```sql
-- 用户-角色关联表
CREATE TABLE sys_user_role (
    user_id     BIGINT NOT NULL,
    role_id     BIGINT NOT NULL,
    create_by   BIGINT COMMENT '授权人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id)
) COMMENT '用户-角色关联';

-- 角色-权限关联表
CREATE TABLE sys_role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    create_by     BIGINT COMMENT '授权人',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id)
) COMMENT '角色-权限关联';

-- 角色自定义数据范围-部门关联表
CREATE TABLE sys_role_dept (
    role_id BIGINT NOT NULL,
    dept_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, dept_id)
) COMMENT '角色自定义数据范围-部门关联（data_scope=5 时使用）';
```

### 3.7 业务表通用约定

每张需租户隔离的业务表必须包含：

```sql
-- 业务表通用字段（必须包含）
tenant_id   BIGINT NOT NULL COMMENT '租户ID',
dept_id     BIGINT COMMENT '归属部门快照（业务创建时写入，调岗不更新）',
owner_id    BIGINT COMMENT '业务负责人ID（用于"仅本人"数据权限）',
create_by   BIGINT COMMENT '创建人（审计用）',
create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
deleted     TINYINT NOT NULL DEFAULT 0,
-- 通用索引
KEY idx_tenant (tenant_id),
KEY idx_tenant_dept (tenant_id, dept_id),
KEY idx_tenant_owner (tenant_id, owner_id)
```

> **说明**：`dept_id` 与 `create_by` 语义不同：前者是业务归属部门快照，后者是创建人审计字段。二者均在 insert 时写入。

---

## 4. 认证与权限加载

### 4.1 登录流程

1. 用户提交 `tenant_code`（或从域名/子路径解析）+ `username` + `password`。
2. 按 `tenant_code` 查 `sys_tenant`，校验 `status=1` 且未过期。
3. 按 `(tenant_id, username)` 查用户，校验 BCrypt 密码、`status=1`。
4. 平台超管（`is_super_admin=1`，`tenant_id=0`）可走独立登录入口，或登录后切换到平台端。

### 4.2 UserDetailsService 加载权限

```java
// 伪代码
User user = userMapper.selectByTenantAndUsername(tenantId, username);
List<String> codes;

if (user.getIsSuperAdmin() == 1) {
    // 超管全部权限
    codes = permissionMapper.selectAllPermCodes();
} else {
    codes = permissionMapper.selectPermCodesByUserId(user.getId());
}

List<GrantedAuthority> authorities = codes.stream()
    .filter(StringUtils::hasText)
    .map(SimpleGrantedAuthority::new)  // 不加 ROLE_ 前缀
    .collect(Collectors.toList());
```

**说明**：
- 按钮/菜单鉴权统一走 `hasAuthority(perm_code)`。
- `hasRole` 仅用于少数按角色整体拦截 URL 的场景，需额外加 `ROLE_` + `role_code`。

### 4.3 安全配置要点

- `@EnableMethodSecurity` 开启方法级鉴权。
- URL 规则：`/platform/**` 仅 `is_super_admin=1` 可访问；`/app/**` 需登录。
- 密码编码器：`BCryptPasswordEncoder`。
- 会话中保存当前 `tenant_id` 上下文（超管切换租户时更新）。

---

## 5. 三层过滤实现

### 5.1 MyBatis 拦截器链

执行顺序（不可颠倒）：

1. **租户拦截器**：对所有含 `tenant_id` 列的表，自动追加 `tenant_id = 当前租户`。超管平台端上下文跳过。
2. **数据权限拦截器**：按当前用户角色 `data_scope` 合并后追加条件。超管跳过。
3. **覆盖范围**：select、update、delete，不得只拦 select。

> **实现建议**：基于 MyBatis Interceptor + JSqlParser 解析 SQL，或采用 MyBatis-Plus 的 `TenantLineInnerInterceptor`（租户）+ 自定义 `DataPermissionInterceptor`（数据权限）。若用 MyBatis-Plus，注意两个拦截器的 `addInnerInterceptor` 顺序。

### 5.2 data_scope 合并规则

- 多角色取并集（最宽范围）。
- 任一角色 `data_scope=1` → 本租户全部，不加数据权限条件。
- `data_scope=2`：`dept_id IN (本部门 + 所有子孙部门) OR owner_id = 当前用户`。
- `data_scope=3`：`dept_id = 当前用户部门 OR owner_id = 当前用户`。
- `data_scope=4`：`owner_id = 当前用户`。
- `data_scope=5`：`dept_id IN (SELECT dept_id FROM sys_role_dept WHERE role_id IN (...)) OR owner_id = 当前用户`。

> **`OR owner_id = 当前用户` 的目的**：保证用户调岗后，本人历史数据仍可见（见第 9 节）。

**子孙部门查询示例**：

```sql
-- 方式一：子查询
SELECT id FROM sys_dept 
WHERE tenant_id = ? 
  AND (id = ? OR ancestors LIKE CONCAT(
    (SELECT ancestors FROM sys_dept WHERE id = ?), ',', ?, '%'
  ));

-- 方式二：前缀匹配
SELECT id FROM sys_dept 
WHERE tenant_id = ? 
  AND ancestors LIKE '0,1,5%';
```

### 5.3 写操作校验

`updateById` / `deleteById` 等按 ID 操作前，必须：

- **方式一**：按 `id + tenant_id + data_scope 条件` 查一次，查不到抛 403；
- **方式二**：改写 SQL 为 `UPDATE ... WHERE id=? AND tenant_id=? AND (数据权限条件)`，校验影响行数 > 0。

> **禁止**：只按 id 操作后再判断归属，存在并发窗口。

### 5.4 拦截器跳过规则

| 场景 | 租户拦截器 | 数据权限拦截器 |
|------|------------|----------------|
| 普通租户用户 | 追加 | 追加 |
| 租户管理员（`data_scope=1`） | 追加 | 不追加（本租户全部） |
| 平台超管 · 平台管理端 | 跳过 | 跳过 |
| 平台超管 · 租户业务端（已切换租户） | 追加（按选定租户） | 跳过 |
| 系统初始化/定时任务 | 按显式传入的 tenantId | 不追加 |

> **注意**：跳过必须由显式上下文标记控制（如 `TenantContext.ignore()`），不得靠 SQL 内容猜测。

---

## 6. Thymeleaf 页面控制

### 6.1 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.thymeleaf.extras</groupId>
    <artifactId>thymeleaf-extras-springsecurity6</artifactId>
</dependency>
```

### 6.2 模板用法

```html
<html xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<body>
    <button sec:authorize="hasAuthority('user:add')">新增用户</button>
    <button sec:authorize="hasAuthority('user:delete')">删除</button>
    <div sec:authorize="hasAuthority('platform:tenant:list')">租户管理入口</div>
</body>
</html>
```

### 6.3 菜单树渲染

1. 按当前用户 + 当前租户查 `type IN (1,2)` 且有权限的菜单。
2. 扁平列表在 Java 中按 `parent_id` 组装成树，传给模板渲染侧边栏。
3. 无权限菜单不出现，页面入口自然消失。
4. 按钮不需要预查树，直接 `hasAuthority` 判断（权限码已在 Authentication 中）。

**菜单查询 SQL 示例**：

```sql
SELECT DISTINCT p.*
FROM sys_permission p
JOIN sys_role_permission rp ON rp.permission_id = p.id
JOIN sys_user_role ur       ON ur.role_id = rp.role_id
JOIN sys_role r             ON r.id = ur.role_id
WHERE ur.user_id = #{userId}
  AND r.tenant_id = #{tenantId}
  AND r.status = 1 AND r.deleted = 0
  AND p.type IN (1, 2)
  AND p.status = 1 AND p.deleted = 0
ORDER BY p.parent_id, p.sort;
```

### 6.4 动态渲染场景（AJAX 局部刷新）

1. 登录时后端下发当前用户 `perm_code` 列表，前端存内存（或 sessionStorage）。
2. JS 动态渲染的按钮由该列表驱动显隐。
3. 安全仍靠后端 `@PreAuthorize`，前端仅做展示。

---

## 7. 后端接口鉴权

```java
@RestController
@RequestMapping("/user")
public class UserController {

    @PreAuthorize("hasAuthority('user:add')")
    @PostMapping("/add")
    public Result addUser(@RequestBody UserDTO dto) { ... }

    @PreAuthorize("hasAuthority('user:delete')")
    @DeleteMapping("/{id}")
    public Result deleteUser(@PathVariable Long id) { ... }
}
```

### 7.1 权限码常量管理

```java
public final class PermCodes {
    public static final String USER_ADD = "user:add";
    public static final String USER_DELETE = "user:delete";
    // ...
}
```

**约定**：
- Controller 的 `@PreAuthorize` 引用常量。
- Thymeleaf 模板字符串无法引用 Java 常量，需保证数据库 `perm_code` 与常量一致。
- `perm_code` 上线后不可随意修改；如必须改，需同步改常量、DB、模板，并走发布流程。

### 7.2 平台端接口

```java
@PreAuthorize("hasAuthority('platform:tenant:list') and @authChecker.isSuperAdmin()")
@GetMapping("/platform/tenant/list")
public Result listTenants() { ... }
```

**双重校验**：功能权限码 + `is_super_admin` 标记。防止权限码误配给租户管理员导致越权。

---

## 8. 跨租户引用校验（Service 层必做）

以下场景必须校验 `tenant_id` 一致，数据库外键无法保证：

| 场景 | 校验规则 |
|------|----------|
| 给用户分配角色 | `user.tenant_id == role.tenant_id` |
| 给角色分配权限 | `role.tenant_id == 当前操作者租户`（超管除外） |
| 给用户设置部门 | `user.tenant_id == dept.tenant_id` |
| 角色配置自定义数据范围部门 | `role.tenant_id == dept.tenant_id` |
| 任何按 ID 引用的跨表操作 | 查询时带 `tenant_id` 条件 |

**实现方式**：Service 层统一封装 `TenantValidator`，所有关联操作前调用校验，失败抛 403 Forbidden。

> **禁止**：依赖数据库外键做跨租户校验（外键只看 id 不看 tenant）。

---

## 9. 归属字段语义与调岗处理

### 9.1 两个概念必须区分

| 字段 | 含义 | 何时写入 | 是否随调岗变 |
|------|------|----------|--------------|
| 业务表 `dept_id` | 业务数据归属部门快照 | 业务创建时 | 不变 |
| `sys_user.dept_id` | 用户当前部门 | 调岗时更新 | 变 |

> 数据权限过滤时，用用户当前部门圈定范围，与业务数据归属部门比对。用户调岗后，可见范围变了，但历史数据归属不变。

### 9.2 归属字段语义（定死）

- 业务表 `dept_id` = 业务创建时操作人所在部门快照，insert 时写入，调岗不自动更新。
- 业务表 `owner_id` = 业务负责人，insert 时写入（可能等于创建人，也可能表单指定）。
- `create_by` = 创建人审计字段，与数据权限无关。

### 9.3 调岗动作

调整用户部门时（`sys_user.dept_id` 变更）：

1. 更新 `sys_user.dept_id`。
2. 清掉该用户的权限/数据范围缓存。
3. **禁止**批量更新业务表的 `dept_id`。

### 9.4 调岗后本人历史数据可见性

`data_scope=2/3/5` 的过滤条件统一附加 `OR owner_id = 当前用户`，保证：

- 用户调岗后，本人负责的历史数据仍可见（`owner_id` 未变）。
- 本部门/本部门及以下的新范围数据也可见。

> 若业务方不接受此语义（要求严格纯部门范围），则需额外提供"我的数据"独立查询入口，该入口绕过 `data_scope`，强制 `owner_id = 当前用户`。

### 9.5 部门合并/拆分/层级调整

- **改名**：`sys_dept.dept_name` 改，业务表 `dept_id` 不变。
- **合并**：人员调到新部门，旧部门停用；历史业务数据 `dept_id` 仍指向旧部门。如需迁移，走显式迁移工具，记录日志（谁、何时、哪些数据从哪个部门迁到哪个部门）。
- **拆分**：同理，历史数据默认不动。
- **层级调整**：必须同步更新 `ancestors` 字段，否则子孙部门查询出错。

> **原则**：组织架构变更时，业务数据 `dept_id` 默认不动；需要动时走显式迁移 + 日志，不做隐式自动更新。

---

## 10. 缓存规范

| 缓存内容 | Key 规范 | 失效时机 |
|----------|----------|----------|
| 用户权限码 | `perm:{tenantId}:{userId}` | 用户角色变更、角色权限变更 |
| 菜单树 | `menu:{tenantId}:{userId}` | 同上 |
| 用户数据范围 | `dataScope:{tenantId}:{userId}` | 用户调岗、角色 `data_scope` 变更 |
| 业务数据 | `biz:{tenantId}:{bizKey}:{scopeHash}` | 业务数据变更 |

**硬性要求**：

1. 所有缓存 key 必须含 `tenantId`。
2. 涉及数据权限的业务缓存，key 必须含数据范围标识（userId 或部门 ID 集合哈希），否则 A 用户会读到 B 用户缓存的数据。
3. 权限/角色/部门变更时，主动失效相关 key（可按 `tenantId` 前缀批量失效）。

---

## 11. 索引与性能

### 11.1 核心索引

| 表 | 索引 |
|----|------|
| `sys_user` | `uk_tenant_username`、`idx_tenant_dept` |
| `sys_role` | `uk_tenant_role_code` |
| `sys_dept` | `idx_tenant_parent`、`idx_tenant_ancestors` |
| `sys_permission` | `uk_perm_code` |
| 关联表 | 联合主键即索引 |
| 业务表 | `idx_tenant`、`idx_tenant_dept`、`idx_tenant_owner` |

### 11.2 性能注意

1. 部门 ID 集合过大时，用 `ancestors` 前缀匹配替代超长 IN 列表。
2. 数据权限条件中的子查询（如子孙部门）建议先查出 ID 列表再拼 IN，或缓存部门树。
3. 分页查询的 count SQL 与 list SQL 必须加相同的租户/数据权限条件，否则总数与列表对不上。
4. 列表查询避免 `SELECT *`，减少不必要字段传输。

---

## 12. 超管跨租户上下文设计

### 12.1 两种形态必须区分

| 形态 | 路径 | 租户拦截器 | 说明 |
|------|------|------------|------|
| 平台管理端 | `/platform/**` | 跳过 | 真正跨租户查询（租户管理、全局统计） |
| 租户业务端 | `/app/**` | 按选定租户追加 | 超管先"切换当前租户"，业务页面仍按单租户过滤 |

### 12.2 租户上下文

```java
public class TenantContext {
    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IGNORE = ThreadLocal.withInitial(() -> false);

    public static Long getTenantId() { return CURRENT_TENANT.get(); }
    public static void setTenantId(Long id) { CURRENT_TENANT.set(id); }
    public static boolean isIgnore() { return IGNORE.get(); }
    public static void setIgnore(boolean ignore) { IGNORE.set(ignore); }
    public static void clear() { CURRENT_TENANT.remove(); IGNORE.remove(); }
}
```

- 普通用户登录后，`TenantContext.setTenantId(user.tenantId)`。
- 超管进入平台端，`TenantContext.setIgnore(true)`。
- 超管切换到某租户业务端，`TenantContext.setTenantId(selectedTenantId)`，`setIgnore(false)`。
- 请求结束时 `clear()`（Filter 或 Interceptor 中 finally）。

### 12.3 禁止事项

- 禁止超管在业务列表默认混查所有租户数据。
- 禁止用 `data_scope=1` 冒充超管穿透。
- 禁止靠 SQL 内容猜测是否跳过拦截器，必须由显式上下文标记控制。

---

## 13. 实施顺序

1. 建表：`sys_tenant`、`sys_user`、`sys_role`、`sys_dept`、`sys_permission`、三张关联表。
2. 初始化 `sys_permission` 全量功能定义（目录/菜单/按钮），同步生成 `PermCodes` 常量类。
3. 实现登录 + `UserDetailsService`（含超管全权限分支）。
4. 实现 `TenantContext` + 租户拦截器（先），再实现数据权限拦截器（后），确保顺序。
5. 实现菜单树查询与 Thymeleaf 侧边栏渲染。
6. Controller 加 `@PreAuthorize`，模板加 `sec:authorize`。
7. 实现写操作的租户 + 数据权限校验。
8. 实现跨租户引用校验（`TenantValidator`）。
9. 加缓存（key 带 `tenant_id` 和数据范围标识）。
10. 超管平台端：租户管理、切换租户上下文、全局统计。
11. 回归测试（对照第 14 节验收清单）。

---

## 14. 验收清单

- [ ] A 租户用户在任何列表/详情/导出接口都看不到 B 租户数据（含手动改 URL ID）。
- [ ] A 租户管理员 `data_scope=1` 只能看本租户全部，看不到其他租户。
- [ ] 平台超管在平台端能跨租户查询；进入租户业务端后按选定租户过滤，不混查。
- [ ] 无 `user:add` 权限的用户，页面无"新增"按钮，且直接 POST 接口返回 403。
- [ ] 删除/修改接口对越权 ID 返回 403，且数据未被改动。
- [ ] 逻辑删除后同名用户可在同租户重建（按最终选定的唯一索引方案验证）。
- [ ] 权限/角色变更后，相关缓存失效，用户刷新页面权限生效。
- [ ] 跨租户分配角色/部门被 Service 层拦截。
- [ ] 多角色 `data_scope` 合并为并集，最宽范围生效。
- [ ] `sys_permission` 仅平台超管可维护，租户无法新建权限码。
- [ ] 用户调岗后，本人历史数据仍可见（`owner_id` 条件生效）。
- [ ] 用户调岗后，原部门数据不再可见（除非 `data_scope=1` 或 `owner_id` 匹配）。
- [ ] 部门层级调整后，`data_scope=2` 的子孙部门查询范围正确（`ancestors` 已更新）。
- [ ] 分页查询总数与列表数据一致（count SQL 与 list SQL 过滤条件相同）。
- [ ] 缓存中无跨租户数据串读（抽查 Redis key）。

---

## 15. 明确不做/暂缓事项

- 角色继承（`sys_role.parent_id` 仅预留，暂不实现逻辑）。
- 数据权限规则引擎（复杂多维交叉场景不纳入本期，`data_scope` 五档枚举覆盖常规需求）。
- 独立数据库 / 独立 Schema 的多租户隔离（本期采用共享库共享表 + `tenant_id`）。
- 超管在业务端"混查全部租户"（明确禁止，改为切换租户上下文）。
- 业务数据自动迁移（部门合并/拆分时，仅提供显式迁移工具，不自动批量更新）。

---

## 16. 风险提示（实现时重点盯）

| 风险点 | 说明 |
|--------|------|
| 拦截器漏拦写操作 | 越权改删。必须覆盖 select/update/delete。 |
| 缓存 key 缺 `tenant_id` | 跨租户串数据。上线前全量排查缓存 key。 |
| 唯一索引未改联合 | 不同租户无法同名。建表时即改。 |
| 超管用 `data_scope=1` 冒充 | 租户管理员穿透。必须用 `is_super_admin` 独立识别。 |
| `perm_code` 数据库与代码常量脱钩 | 按钮显隐与接口鉴权不一致。约定上线后不可改码，或提供校验脚本。 |
| 业务表缺 `dept_id`/`owner_id` | 数据权限无法落地。建业务表时即加，insert 时写入。 |
| 详情/导出接口漏加数据权限 | 列表安全但详情泄露。所有按 ID 查询统一走权限校验。 |
| 调岗时批量更新业务表 `dept_id` | 历史归属失真、不可逆。明确禁止。 |
| 部门层级调整未更新 `ancestors` | 子孙部门查询错误。组织架构变更时同步维护。 |
| `TenantContext` 未在请求结束时 `clear()` | 线程池复用导致租户串数据。Filter finally 中必须清理。 |
