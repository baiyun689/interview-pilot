# 登录与数据归属 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加服务端 Session 登录，并让现有简历、面试、岗位和异步任务全部按当前用户隔离。

**Architecture:** Spring Security 负责认证和 CSRF，Spring Session Data Redis 保存登录 Session。Controller 从 `CurrentUserProvider` 获取当前用户，并把 `CurrentUser` 显式传入应用层；Repository 使用资源 ID 与 `userAccountId` 联合查询。

**Tech Stack:** Spring Security、Spring Session Data Redis、MySQL/Flyway、JUnit 5、MockMvc、React、TypeScript、Vitest

## Global Constraints

- Session Cookie 使用 HttpOnly、SameSite=Lax；公开 HTTPS 环境启用 Secure。
- CSRF 保持开启，前端从 `XSRF-TOKEN` Cookie 读取 Token 并发送 `X-XSRF-TOKEN`。
- 除 `/api/auth/register`、`/api/auth/login` 和 `/actuator/health` 外，应用接口都要求登录。
- Controller 不得先全局查询资源再判断归属；所有资源查询都必须在 Repository 条件中包含 `userAccountId`。
- 旧数据统一归属到状态为 `DISABLED` 的 `legacy-demo` 用户。

---

### Task 1: 用户表、用户外键和安全依赖

**Files:**
- Modify: `build.gradle`
- Create: `src/main/resources/db/migration/V11__add_users_and_ownership.sql`
- Create: `src/main/java/interview/pilot/auth/domain/UserStatus.java`
- Create: `src/main/java/interview/pilot/auth/infrastructure/UserAccountEntity.java`
- Create: `src/main/java/interview/pilot/auth/infrastructure/UserAccountRepository.java`
- Test: `src/test/java/interview/pilot/persistence/UserOwnershipV11MigrationIT.java`

**Interfaces:**
- Produces: `UserAccountRepository.findByEmail(String)`、`UserAccountRepository.findByUserId(UUID)`。
- Produces: 所有现有聚合根的非空 `user_account_id BIGINT` 列。

- [ ] **Step 1: 写失败的迁移集成测试**

```java
@Test
void v11BackfillsLegacyOwnerAndScopesResumeHash() {
  Integer users = jdbc.queryForObject(
      "select count(*) from user_account where email = 'legacy-demo@invalid.local'",
      Integer.class);
  Integer missingOwners = jdbc.queryForObject(
      "select count(*) from resume where user_account_id is null", Integer.class);
  assertThat(users).isEqualTo(1);
  assertThat(missingOwners).isZero();
  assertThat(indexExists("resume", "uq_resume_user_hash")).isTrue();
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat test --tests interview.pilot.persistence.UserOwnershipV11MigrationIT`

Expected: FAIL，原因是 V11 和 `user_account` 尚不存在。

- [ ] **Step 3: 添加依赖和迁移**

在 `build.gradle` 添加：

```groovy
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.session:spring-session-data-redis'
testImplementation 'org.springframework.security:spring-security-test'
```

V11 必须完成：

```sql
CREATE TABLE user_account (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id CHAR(36) NOT NULL,
  email VARCHAR(320) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uq_user_account_user_id UNIQUE (user_id),
  CONSTRAINT uq_user_account_email UNIQUE (email),
  CONSTRAINT chk_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO user_account(user_id, email, password_hash, display_name, status)
VALUES ('00000000-0000-0000-0000-000000000001',
        'legacy-demo@invalid.local', '!', 'Legacy Demo', 'DISABLED');

ALTER TABLE resume ADD COLUMN user_account_id BIGINT NULL AFTER id;
ALTER TABLE job_profile ADD COLUMN user_account_id BIGINT NULL AFTER id;
ALTER TABLE interview_session ADD COLUMN user_account_id BIGINT NULL AFTER id;
ALTER TABLE async_task ADD COLUMN user_account_id BIGINT NULL AFTER id;

UPDATE resume SET user_account_id = 1 WHERE user_account_id IS NULL;
UPDATE job_profile SET user_account_id = 1 WHERE user_account_id IS NULL;
UPDATE interview_session SET user_account_id = 1 WHERE user_account_id IS NULL;
UPDATE async_task SET user_account_id = 1 WHERE user_account_id IS NULL;

ALTER TABLE resume DROP INDEX uq_resume_content_hash;
ALTER TABLE resume MODIFY user_account_id BIGINT NOT NULL;
ALTER TABLE job_profile MODIFY user_account_id BIGINT NOT NULL;
ALTER TABLE interview_session MODIFY user_account_id BIGINT NOT NULL;
ALTER TABLE async_task MODIFY user_account_id BIGINT NOT NULL;

ALTER TABLE resume ADD CONSTRAINT fk_resume_user
  FOREIGN KEY (user_account_id) REFERENCES user_account(id);
ALTER TABLE job_profile ADD CONSTRAINT fk_job_profile_user
  FOREIGN KEY (user_account_id) REFERENCES user_account(id);
ALTER TABLE interview_session ADD CONSTRAINT fk_interview_session_user
  FOREIGN KEY (user_account_id) REFERENCES user_account(id);
ALTER TABLE async_task ADD CONSTRAINT fk_async_task_user
  FOREIGN KEY (user_account_id) REFERENCES user_account(id);

CREATE UNIQUE INDEX uq_resume_user_hash ON resume(user_account_id, content_hash);
CREATE INDEX idx_resume_user_created ON resume(user_account_id, created_at);
CREATE INDEX idx_interview_user_created ON interview_session(user_account_id, created_at);
CREATE INDEX idx_async_task_user_task ON async_task(user_account_id, task_id);
```

- [ ] **Step 4: 实现用户实体和 Repository**

```java
public enum UserStatus { ACTIVE, DISABLED }

public interface UserAccountRepository extends JpaRepository<UserAccountEntity, Long> {
  Optional<UserAccountEntity> findByEmail(String email);
  Optional<UserAccountEntity> findByUserId(UUID userId);
  boolean existsByEmail(String email);
}
```

`UserAccountEntity.register(email, passwordHash, displayName)` 负责创建 UUID、设置 `ACTIVE`，并保留 JPA 时间戳和 `@Version`。

- [ ] **Step 5: 运行迁移和上下文测试**

Run: `.\gradlew.bat test --tests interview.pilot.persistence.UserOwnershipV11MigrationIT --tests interview.pilot.InterviewPilotApplicationTest`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add build.gradle src/main/resources/db/migration/V11__add_users_and_ownership.sql src/main/java/interview/pilot/auth src/test/java/interview/pilot/persistence/UserOwnershipV11MigrationIT.java
git commit -m "feat: add users and ownership schema"
```

### Task 2: 后端注册、登录、Session 和 CSRF

**Files:**
- Create: `src/main/java/interview/pilot/auth/application/CurrentUser.java`
- Create: `src/main/java/interview/pilot/auth/application/AuthenticatedUser.java`
- Create: `src/main/java/interview/pilot/auth/application/CurrentUserProvider.java`
- Create: `src/main/java/interview/pilot/auth/application/AuthService.java`
- Create: `src/main/java/interview/pilot/auth/api/RegisterRequest.java`
- Create: `src/main/java/interview/pilot/auth/api/LoginRequest.java`
- Create: `src/main/java/interview/pilot/auth/api/CurrentUserResponse.java`
- Create: `src/main/java/interview/pilot/auth/api/AuthController.java`
- Create: `src/main/java/interview/pilot/auth/config/SecurityConfig.java`
- Create: `src/main/java/interview/pilot/auth/config/CsrfCookieFilter.java`
- Modify: `src/main/java/interview/pilot/common/ratelimit/RateLimitScope.java`
- Modify: `src/main/java/interview/pilot/common/ratelimit/RateLimitAspect.java`
- Test: `src/test/java/interview/pilot/auth/api/AuthControllerTest.java`
- Test: `src/test/java/interview/pilot/common/ratelimit/RateLimitAspectTest.java`

**Interfaces:**
- Produces: `record CurrentUser(Long databaseId, UUID userId, String email, String displayName)`。
- Produces: `CurrentUser CurrentUserProvider.require()`。
- Produces: `/api/auth/register|login|logout|me`。

- [ ] **Step 1: 写失败的 Auth MockMvc 测试**

```java
@Test
void registerCreatesSessionAndMeReturnsTheUser() throws Exception {
  MvcResult result = mvc.perform(post("/api/auth/register")
      .with(csrf())
      .contentType(APPLICATION_JSON)
      .content("""
          {"email":"User@Example.com","password":"correct horse battery staple",
           "displayName":"小白"}
          """))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.email").value("user@example.com"))
      .andReturn();

  mvc.perform(get("/api/auth/me").session((MockHttpSession) result.getRequest().getSession()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.displayName").value("小白"));
}
```

同时覆盖重复邮箱返回 `409 EMAIL_ALREADY_EXISTS`、错误密码返回 `401 AUTHENTICATION_FAILED`、禁用用户不能登录、匿名访问 `/api/resumes` 返回 401。

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat test --tests interview.pilot.auth.api.AuthControllerTest`

Expected: FAIL，Auth Controller 和 Security 配置不存在。

- [ ] **Step 3: 实现认证类型和服务**

```java
public record CurrentUser(
    Long databaseId, UUID userId, String email, String displayName) {}

public record AuthenticatedUser(
    Long databaseId, UUID userId, String email, String displayName,
    String password, boolean enabled) implements UserDetails {
  @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
  @Override public String getUsername() { return email; }
  @Override public boolean isEnabled() { return enabled; }
}
```

`AuthService.register` 规范化邮箱、使用 `PasswordEncoder` 哈希密码并保存用户；`UserDetailsService` 从 `UserAccountRepository.findByEmail` 加载用户。

- [ ] **Step 4: 实现 SecurityConfig 和 AuthController**

```java
@Bean
SecurityFilterChain security(HttpSecurity http) throws Exception {
  var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
  csrf.setCookieName("XSRF-TOKEN");
  csrf.setHeaderName("X-XSRF-TOKEN");
  return http
      .csrf(config -> config.csrfTokenRepository(csrf))
      .authorizeHttpRequests(auth -> auth
          .requestMatchers("/api/auth/register", "/api/auth/login", "/actuator/health").permitAll()
          .anyRequest().authenticated())
      .exceptionHandling(errors -> errors.authenticationEntryPoint(
          (request, response, exception) -> response.sendError(401)))
      .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
      .build();
}

@Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
```

登录端点使用 `AuthenticationManager.authenticate`，成功后把认证写入
`SecurityContextHolder`，并通过
`securityContextRepository.saveContext(context, request, response)` 保存到 Session。

`CsrfCookieFilter` 必须访问延迟 Token，确保匿名客户端第一次 `GET /api/auth/me` 即使得到
401，也会收到 `XSRF-TOKEN` Cookie：

```java
public final class CsrfCookieFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (token != null) token.getToken();
    chain.doFilter(request, response);
  }
}
```

新增 `RateLimitScope.USER`。`RateLimitAspect` 对 USER 使用
`CurrentUserProvider.require().userId()` 构造 Bucket，不能使用可伪造 Header。登录、上传和创建面试的昂贵写接口同时保留 IP 限流并增加 USER 限流。

- [ ] **Step 5: 运行 Auth 测试**

Run: `.\gradlew.bat test --tests interview.pilot.auth.api.AuthControllerTest --tests interview.pilot.common.ratelimit.RateLimitAspectTest`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/interview/pilot/auth src/test/java/interview/pilot/auth
git commit -m "feat: add session authentication"
```

### Task 3: 前端登录状态、CSRF 和路由保护

**Files:**
- Modify: `frontend/src/api/request.ts`
- Create: `frontend/src/api/auth.ts`
- Create: `frontend/src/types/auth.ts`
- Create: `frontend/src/auth/AuthProvider.tsx`
- Create: `frontend/src/auth/ProtectedRoute.tsx`
- Create: `frontend/src/pages/LoginPage.tsx`
- Create: `frontend/src/pages/RegisterPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/main.tsx`
- Modify: `frontend/src/components/Layout.tsx`
- Test: `frontend/src/App.test.tsx`
- Test: `frontend/src/api/request.test.ts`

**Interfaces:**
- Produces: `useAuth(): { user, loading, login, register, logout }`。
- Consumes: Task 2 的 Auth HTTP 接口和 `XSRF-TOKEN` Cookie。

- [ ] **Step 1: 写失败的请求和路由测试**

```ts
it('adds the CSRF header to state-changing requests', async () => {
  document.cookie = 'XSRF-TOKEN=csrf-123'
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ ok: true })))
  await request('/api/example', { method: 'POST', body: '{}' })
  expect(fetch).toHaveBeenCalledWith('/api/example', expect.objectContaining({
    credentials: 'same-origin',
    headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-123' }),
  }))
})
```

`App.test.tsx` 覆盖匿名访问 `/resumes` 跳转 `/login`，登录用户访问 `/login` 跳转 `/resumes`，退出后回到登录页。

- [ ] **Step 2: 运行并确认失败**

Run: `cd frontend; pnpm exec vitest run src/api/request.test.ts src/App.test.tsx`

Expected: FAIL，未设置 CSRF Header，也没有 Auth Provider。

- [ ] **Step 3: 修改请求客户端**

```ts
function csrfToken(): string | null {
  const match = document.cookie.split('; ')
    .find((part) => part.startsWith('XSRF-TOKEN='))
  return match ? decodeURIComponent(match.slice('XSRF-TOKEN='.length)) : null
}

function authenticatedInit(init: RequestInit = {}): RequestInit {
  const headers = new Headers(init.headers)
  const method = (init.method ?? 'GET').toUpperCase()
  const token = csrfToken()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && token) {
    headers.set('X-XSRF-TOKEN', token)
  }
  return { ...init, headers, credentials: 'same-origin' }
}
```

`requestWithMeta` 必须把 `authenticatedInit(init)` 传给 `fetch`。

- [ ] **Step 4: 实现 AuthProvider 和页面**

`AuthProvider` 首次调用 `GET /api/auth/me`；401 视为匿名，其他错误保留为错误状态。`ProtectedRoute` 在加载时显示状态，匿名时 `<Navigate to="/login" replace />`。Layout 底部显示当前用户和退出按钮。

- [ ] **Step 5: 运行前端测试和构建**

Run: `cd frontend; pnpm exec vitest run src/api/request.test.ts src/App.test.tsx; pnpm build`

Expected: 测试 PASS，Vite build 成功。

- [ ] **Step 6: 提交**

```bash
git add frontend/src
git commit -m "feat: add authenticated frontend shell"
```

### Task 4: 简历和异步任务按用户隔离

**Files:**
- Modify: `src/main/java/interview/pilot/resume/infrastructure/ResumeEntity.java`
- Modify: `src/main/java/interview/pilot/resume/infrastructure/ResumeRepository.java`
- Modify: `src/main/java/interview/pilot/resume/application/ResumeUploadService.java`
- Modify: `src/main/java/interview/pilot/resume/application/ResumeQueryService.java`
- Modify: `src/main/java/interview/pilot/resume/api/ResumeController.java`
- Modify: `src/main/java/interview/pilot/async/infrastructure/AsyncTaskEntity.java`
- Modify: `src/main/java/interview/pilot/async/infrastructure/AsyncTaskRepository.java`
- Modify: `src/main/java/interview/pilot/async/application/AsyncTaskService.java`
- Modify: `src/main/java/interview/pilot/async/api/AsyncTaskController.java`
- Test: `src/test/java/interview/pilot/resume/api/ResumeOwnershipIT.java`
- Test: `src/test/java/interview/pilot/async/api/AsyncTaskOwnershipIT.java`

**Interfaces:**
- Consumes: `CurrentUser`。
- Produces: `upload(CurrentUser, MultipartFile)`、`list(CurrentUser)`、`get(CurrentUser, Long)`。

- [ ] **Step 1: 写两个用户隔离的失败测试**

```java
@Test
void userCannotReadAnotherUsersResume() throws Exception {
  Long resumeId = fixtures.readyResume(userA.databaseId());
  mvc.perform(get("/api/resumes/{id}", resumeId).with(user(userB.principal())))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value("RESUME_NOT_FOUND"));
}
```

异步任务测试使用用户 B 查询和重试用户 A 的任务，均返回 404。

- [ ] **Step 2: 运行并确认失败**

Run: `.\gradlew.bat test --tests interview.pilot.resume.api.ResumeOwnershipIT --tests interview.pilot.async.api.AsyncTaskOwnershipIT`

Expected: FAIL，现有查询未带用户范围。

- [ ] **Step 3: 修改实体、Repository 和应用接口**

```java
Optional<ResumeEntity> findByIdAndUserAccountId(Long id, Long userAccountId);
Optional<ResumeEntity> findByUserAccountIdAndContentHash(Long userAccountId, String contentHash);
List<ResumeEntity> findAllByUserAccountIdOrderByCreatedAtDesc(Long userAccountId);

Optional<AsyncTaskEntity> findByTaskIdAndUserAccountId(UUID taskId, Long userAccountId);
Optional<AsyncTaskEntity> findByTaskTypeAndBizKeyAndUserAccountId(
    AsyncTaskType type, String bizKey, Long userAccountId);
```

`ResumeEntity.pending` 和 `AsyncTaskEntity.pending` 增加 `userAccountId` 参数。上传去重仅在当前用户范围内执行。Controller 使用 `CurrentUserProvider.require()` 并显式传入 Service。

- [ ] **Step 4: 运行相关测试**

Run: `.\gradlew.bat test --tests 'interview.pilot.resume.*' --tests 'interview.pilot.async.*'`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/interview/pilot/resume src/main/java/interview/pilot/async src/test/java/interview/pilot/resume src/test/java/interview/pilot/async
git commit -m "feat: isolate resumes and tasks by user"
```

### Task 5: 面试查询、创建、答题和报告按用户隔离

**Files:**
- Modify: `src/main/java/interview/pilot/interview/infrastructure/InterviewSessionEntity.java`
- Modify: `src/main/java/interview/pilot/interview/infrastructure/InterviewSessionRepository.java`
- Modify: `src/main/java/interview/pilot/interview/infrastructure/JobProfileEntity.java`
- Modify: `src/main/java/interview/pilot/interview/application/CreateInterviewService.java`
- Modify: `src/main/java/interview/pilot/interview/application/InterviewCreation.java`
- Modify: `src/main/java/interview/pilot/interview/infrastructure/JpaInterviewCreationStore.java`
- Modify: `src/main/java/interview/pilot/interview/application/InterviewQueryService.java`
- Modify: `src/main/java/interview/pilot/interview/application/SubmitAnswerService.java`
- Modify: `src/main/java/interview/pilot/interview/application/InterviewSseService.java`
- Modify: `src/main/java/interview/pilot/interview/api/InterviewController.java`
- Test: `src/test/java/interview/pilot/interview/api/InterviewOwnershipIT.java`
- Test: `src/test/java/interview/pilot/e2e/InterviewJourneyIT.java`

**Interfaces:**
- Consumes: `CurrentUser`。
- Produces: 所有面试入口显式接收 `CurrentUser`。

- [ ] **Step 1: 写失败的跨用户面试测试**

```java
@Test
void userCannotCreateFromOrReadAnotherUsersInterview() throws Exception {
  Long foreignResume = fixtures.readyResume(userA.databaseId());
  mvc.perform(post("/api/interviews").with(user(userB.principal())).with(csrf())
      .contentType(APPLICATION_JSON)
      .content(createRequest(foreignResume)))
      .andExpect(status().isNotFound());

  UUID sessionId = fixtures.interview(userA.databaseId());
  mvc.perform(get("/api/interviews/{id}", sessionId).with(user(userB.principal())))
      .andExpect(status().isNotFound());
}
```

同一测试覆盖列表、报告和答题 SSE 越权。

- [ ] **Step 2: 运行并确认失败**

Run: `.\gradlew.bat test --tests interview.pilot.interview.api.InterviewOwnershipIT`

Expected: FAIL。

- [ ] **Step 3: 修改 Repository 和应用接口**

```java
Optional<InterviewSessionEntity> findBySessionIdAndUserAccountId(
    UUID sessionId, Long userAccountId);
List<InterviewSessionEntity> findAllByUserAccountIdOrderByCreatedAtDesc(Long userAccountId);
```

`InterviewCreation` 增加 `Long userAccountId`；`InterviewSessionEntity.create` 和
`JobProfileEntity.create` 保存该 ID。创建面试先调用
`resumes.findByIdAndUserAccountId(request.resumeId(), user.databaseId())`。

`InterviewQueryService.get/list/report`、`InterviewSseService.stream` 和
`SubmitAnswerService` 都使用带用户范围的 Session 查询。越权统一表现为 404。

- [ ] **Step 4: 更新现有测试 Fixture 并运行回归**

Run: `.\gradlew.bat test --tests 'interview.pilot.interview.*' --tests interview.pilot.e2e.InterviewJourneyIT`

Expected: PASS，现有旅程在指定测试用户后行为不变。

- [ ] **Step 5: 运行全阶段验证**

Run: `.\gradlew.bat test; cd frontend; pnpm exec vitest run; pnpm build; cd ..; git diff --check`

Expected: 后端、前端测试和构建全部通过，diff check 无输出。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/interview/pilot/interview src/test/java/interview/pilot/interview src/test/java/interview/pilot/e2e
git commit -m "feat: isolate interviews by user"
```
