package com.ainative.v2.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 接口集成测试基类。
 *
 * 环境:Testcontainers 起真实 MySQL 8,按生产同一份三段 schema(core → rest → workitem)
 * 建表——SQL 方言(seekdb/MySQL)不做任何仿真,测的就是真实行为。schema 文件从仓库
 * docs/ 直接读取,与 docker-compose 的 db-init 完全同源,不维护第二份。
 *
 * 隔离:每个用例用唯一用户名注册独立用户/工作区,互不污染;类内产生的派发、执行等
 * 下游数据也挂在各自工作区下。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    public static final ObjectMapper JSON = new ObjectMapper();

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("ainative_test")
            .withUsername("test")
            .withPassword("test");

    static volatile boolean schemaReady;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    static final AtomicLong USER_SEQ = new AtomicLong(1000);

    /** 外部数据库(可选):TEST_JDBC_URL 指定时直连,不启 Testcontainers。 */
    static final String EXT_JDBC_URL = System.getenv("TEST_JDBC_URL");
    static final String EXT_JDBC_USER = System.getenv().getOrDefault("TEST_JDBC_USER", "test");
    static final String EXT_JDBC_PASSWORD = System.getenv().getOrDefault("TEST_JDBC_PASSWORD", "test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (EXT_JDBC_URL != null && !EXT_JDBC_URL.isBlank()) {
            registry.add("spring.datasource.url", () -> EXT_JDBC_URL);
            registry.add("spring.datasource.username", () -> EXT_JDBC_USER);
            registry.add("spring.datasource.password", () -> EXT_JDBC_PASSWORD);
        } else {
            MYSQL.start();
            registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
            registry.add("spring.datasource.username", MYSQL::getUsername);
            registry.add("spring.datasource.password", MYSQL::getPassword);
        }
        registry.add("ainative.jwt-secret", () -> "test-secret-please-change-32-chars!!");
        registry.add("ainative.public-base-url", () -> "http://localhost:7011");
        registry.add("ainative.download-secret", () -> "test-download-secret");
    }

    /** 建立到测试库的连接(外部库直连,否则连 Testcontainers)。 */
    private static java.sql.Connection openSchemaConnection() throws Exception {
        if (EXT_JDBC_URL != null && !EXT_JDBC_URL.isBlank()) {
            return java.sql.DriverManager.getConnection(EXT_JDBC_URL, EXT_JDBC_USER, EXT_JDBC_PASSWORD);
        }
        return MYSQL.createConnection("");
    }

    /** 定位仓库内生产 schema 文件(user.dir = server-v2 模块目录)。 */
    private static Path schemaFile(String name) {
        Path module = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        List<Path> candidates = new ArrayList<>();
        // server-v2 与 docs 同级:platform/ainative/{server-v2,docs}
        Path parent = module.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("docs").resolve(name));
            Path grand = parent.getParent();
            if (grand != null) {
                candidates.add(grand.resolve("platform").resolve("ainative").resolve("docs").resolve(name));
                candidates.add(grand.resolve("docs").resolve(name));
            }
        }
        candidates.add(module.resolve("docs").resolve(name));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("找不到 schema 文件: " + name + "(从 " + module + " 起尝试过 " + candidates + ")");
    }

    /** 按生产同一顺序执行三段 schema(core → rest → workitem)。全 JVM 只执行一次。 */
    @BeforeAll
    static void initSchemaOnce() throws Exception {
        if (schemaReady) return;
        synchronized (IntegrationTestBase.class) {
            if (schemaReady) return;
            try (var conn = openSchemaConnection()) {
                for (String f : new String[]{
                        "seekdb-schema-v1-core.sql", "seekdb-schema-v1-rest.sql", "seekdb-schema-v1-workitem.sql"}) {
                    String sql = Files.readString(schemaFile(f), StandardCharsets.UTF_8);
                    for (String raw : sql.split(";")) {
                        // 只剥掉注释行,不能整条跳过:文件首条 CREATE TABLE 前有注释行,
                        // 整条跳过会静默丢掉第一张表。
                        String trimmed = raw.lines()
                                .filter(line -> !line.strip().startsWith("--"))
                                .collect(java.util.stream.Collectors.joining("\n"))
                                .strip();
                        if (trimmed.isEmpty()) continue;
                        try (var st = conn.createStatement()) {
                            st.execute(trimmed);
                        } catch (Exception e) {
                            // 建表失败必须暴露:吞掉会得到一个"空库",测试通过但毫无意义。
                            // seekdb 专属语句(与生产 db-init 同款)在 MySQL 下报语法错,单独记录后继续。
                            if (!isSeekdbOnly(trimmed)) {
                                throw new IllegalStateException("schema 建表失败(" + f + "): " + trimmed
                                        + " → " + e.getMessage(), e);
                            }
                        }
                    }
                }
            }
            schemaReady = true;
        }
    }


    /** seekdb 专属语法在 MySQL 下不支持:这些语句跳过不算失败。 */
    private static boolean isSeekdbOnly(String stmt) {
        String s = stmt.toLowerCase();
        return s.contains("engine=seekdb") || s.contains("catalog") || s.contains("resource group")
                || s.contains("alter table") && s.contains("comment");
    }

    /** 注册并登录一个测试用户,返回 {userId, username, workspaceId, token}。workspace 随注册自动创建。 */
    protected Map<String, Object> newUser(String tag) throws Exception {
        long seq = USER_SEQ.incrementAndGet();
        // 用户名带进程内随机后缀:序号每 JVM 重启都从 1000 重来,同一个测试库跑第二遍
        // 就会撞「用户名已存在」—— 测试不该要求空库。
        String username = "it_" + tag + "_" + seq + "_"
                + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0xFFFFFFL);
        String registerBody = JSON.writeValueAsString(Map.of(
                "username", username,
                "password", "Passw0rd!" + seq,
                "nickname", "接口测试-" + seq,
                "email", username + "@test.local"));
        var register = mvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(registerBody))
                .andReturn();
        if (register.getResponse().getStatus() != 200) {
            throw new IllegalStateException("注册失败: " + body(register));
        }
        JsonNode data = JSON.readTree(body(register)).path("data");
        long registeredWorkspace = data.path("workspaceId").asLong();
        if (registeredWorkspace <= 0) {
            throw new IllegalStateException("注册未返回有效工作区: " + body(register));
        }
        // 登录不传 workspaceId:由服务端落到该用户的第一个工作区(与前端一致),
        // 不依赖注册接口返回的 workspaceId,避免两处口径不一致。
        String loginBody = JSON.writeValueAsString(Map.of(
                "username", username,
                "password", "Passw0rd!" + seq));
        var login = mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(loginBody))
                .andReturn()
                .getResponse();
        JsonNode loginData = JSON.readTree(body(login)).path("data");
        return Map.of(
                "userId", loginData.path("userId").asLong(),
                "username", username,
                "password", "Passw0rd!" + seq,
                "workspaceId", loginData.path("workspace").asLong(),
                "token", loginData.path("accessToken").asText());
    }

    /**
     * 平台管理员账号。
     *
     * 注意:register 的 firstUser 契约只让「库里第一个用户」成为管理员,而测试类可能
     * 并行注册过其他用户——所以这里不能依赖注册顺序,而是:若库里已有管理员就复用,
     * 没有才注册一个(此时它必然是第一个,自动成为管理员)。
     */
    protected Map<String, Object> adminUser() throws Exception {
        List<Long> adminIds = jdbc.queryForList(
                "SELECT id FROM id_user WHERE is_admin=1 AND deleted_at IS NULL ORDER BY id LIMIT 1", Long.class);
        if (!adminIds.isEmpty()) {
            long adminId = adminIds.get(0);
            String username = jdbc.queryForObject(
                    "SELECT username FROM id_user WHERE id=?", String.class, adminId);
            // 不知道密码(历史注册),若缓存里有就复用,否则现场建一个新管理员
            String cached = ADMIN_CACHE.get(username);
            if (cached != null) {
                var login = mvc.perform(post("/api/auth/login")
                                .contentType("application/json")
                                .content(JSON.writeValueAsString(Map.of("username", username, "password", cached))))
                        .andReturn().getResponse();
                JsonNode data = JSON.readTree(body(login)).path("data");
                return Map.of(
                        "userId", data.path("userId").asLong(),
                        "username", username,
                        "password", cached,
                        "workspaceId", data.path("workspace").asLong(),
                        "token", data.path("accessToken").asText());
            }
        }
        // 库里没有可用密码的管理员:建一个新用户并直接把 is_admin 置 1
        // (测试库,数据无真实意义;生产权限路径由 PermissionInterceptor.isAdmin 决定,与注册顺序无关)
        Map<String, Object> user = newUser("admin");
        jdbc.update("UPDATE id_user SET is_admin=1 WHERE id=?", user.get("userId"));
        return Map.ofEntries(
                Map.entry("userId", user.get("userId")),
                Map.entry("username", user.get("username")),
                Map.entry("password", user.get("password")),
                Map.entry("workspaceId", user.get("workspaceId")),
                Map.entry("token", user.get("token")));
    }

    private static final Map<String, String> ADMIN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    protected String bearer(Map<String, Object> user) {
        return "Bearer " + user.get("token");
    }


    /**
     * 统一按 UTF-8 读取响应体。
     * MockMvc 的 getContentAsString() 默认按 ISO-8859-1 解码,中文会变成乱码;
     * 这里强制 UTF-8,与应用返回的 application/json;charset=UTF-8 一致。
     */
    protected static String body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return body(result.getResponse());
    }

    /** 直接从响应对象读 UTF-8 文本。 */
    protected static String body(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    /** 解析响应包络里的 data 节点(先校验包络 success,失败时抛出真实响应便于定位)。 */
    protected static JsonNode dataOf(String responseBody) throws Exception {
        JsonNode root = JSON.readTree(responseBody);
        if (!root.path("success").asBoolean(false)) {
            throw new IllegalStateException("业务失败: " + responseBody);
        }
        return root.path("data");
    }
}
