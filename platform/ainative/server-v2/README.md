# CodeFlow 服务端(v2)

Spring Boot 3.3 / Java 21 / JdbcTemplate / seekdb（MySQL 协议兼容）的 REST 服务端。
前端构建产物随 jar 内嵌(单镜像部署)。

## 接口文档(Swagger / OpenAPI)

启动后访问:

| 入口 | 地址 | 说明 |
|---|---|---|
| Swagger UI | `http://<host>:7001/swagger-ui.html` | 交互式文档,可直接发请求 |
| OpenAPI JSON | `http://<host>:7001/v3/api-docs` | 机器可读契约,可用于生成客户端 |

- 文档由 `springdoc` **自动扫描全部 `@RestController`** 生成,路径/参数/返回类型都来自真实代码,
  不存在"手写文档与代码不一致"的问题;代码改了文档自动跟着变。
- 40 个控制器按 **11 个中文领域**分组(鉴权与用户 / 需求交付 / 执行与自动化 / 分析度量 /
  配置资产 / 外部连接 / 平台管理 / 开放平台 / 运行节点 / 工作区 / 其他),
  每个端点都有 `@Operation(summary=...)` 说明。
- 鉴权:右上 `Authorize` 填登录拿到的 `accessToken`(`/api/auth/login` 的返回值),
  之后所有请求自动带 `Authorization: Bearer <token>`。
- 关闭文档:环境变量 `AINATIVE_SWAGGER_ENABLED=false`(公网部署不希望暴露接口清单时用)。

## 测试

```bash
# 全量(接口集成 + 单元)
scripts/run-tests.sh

# 单个类
scripts/run-tests.sh -Dtest=WorkitemDeliveryApiTest
scripts/run-tests.sh -Dtest='*ApiTest'
scripts/run-tests.sh -Dtest=SdlcTemplateShapeTest
```

`run-tests.sh` 做的事:

1. 起一个**专用** MySQL 8 容器(`ainative-test-db`,端口 3399,与平台运行库完全隔离),
   每轮强制重建,避免上一轮数据残留;
2. 用 maven 容器(宿主无需装 Maven)跑 `mvn test`,依赖走项目自带
   `scripts/maven-settings.xml`(阿里云镜像);
3. 测试基类按**生产同一份 schema**(`platform/ainative/docs/seekdb-schema-v1-*.sql`,
   与 docker-compose 的 db-init 同源)建表——不维护第二份 DDL;
4. 结束自动清理测试库容器(想保留现场设 `KEEP_TEST_DB=1`)。

### 测试分层

| 层 | 位置 | 说明 |
|---|---|---|
| 接口集成 | `src/test/java/com/ainative/v2/api/` | MockMvc 打真实端点 + 真实 MySQL;每用例注册独立用户/工作区 |
| 纯单元 | `src/test/java/com/ainative/v2/unit/` | 不启动 Spring,验证纯逻辑与文档契约一致性 |
| 测试基类 | `src/test/java/com/ainative/v2/support/IntegrationTestBase.java` | 外部库/Testcontainers 双模式、UTF-8 响应读取、管理员账号获取 |

当前规模:8 个测试类 / 40 个用例,覆盖 40 个 Controller 的主链路与全部已修回归点。

### 已覆盖的回归点(这些曾是真 bug)

| 用例 | 锁死的行为 |
|---|---|
| `WorkitemDeliveryApiTest.workitem_list_filters_by_status_category` | 看板按状态分类过滤(`statusCategory` 曾被后端忽略,导致每列计数都等于总数) |
| `WorkitemDeliveryApiTest.workitem_transition_along_legal_edges_reaches_done` | 按状态机合法边逐步流转到已完成(非法目标必须被拒) |
| `AssetsAndWorkersApiTest.sdlc_create_then_stepCount_matches_steps` | 交付流程列表 `stepCount`(曾被覆盖成 0) |
| `ExecutorAndIntegrationApiTest.executor_register_heartbeat_and_launch_config` | 启动配置读写 + 启动命令生成(曾因 `model_policy` 列缺失 500、`memoryMode` 不落表) |
| `ExecutorAndIntegrationApiTest.dashboard_overview_counts_match_workitems` | 总览 `byLifecycle` 合计 == 工单总数(曾因 NEW 双计导致 9≠5) |
| `OpenApiDocsTest.openapi_json_is_served_with_all_core_domains` | OpenAPI 文档覆盖 32 条核心路由 + bearerAuth + 路径数量下限 |
| `unit.OpenApiContractTest` | 每个 Controller 有 `@Tag`、每个端点有 `@Operation`、summary 引号配对 |
| `unit.SdlcTemplateShapeTest` | `templateShape` 不得覆盖 service 已算好的 `stepCount` |

## 本地运行

```bash
# 依赖齐备的完整栈(seekdb + schema init + app)
cd platform && docker compose up -d

# 或单独构建镜像
docker build -f Dockerfile.v2 -t ainative-server-v2:dev .
```

关键环境变量见 `src/main/resources/application.yml`(`DB_*`、`AINATIVE_JWT_SECRET`、
`AINATIVE_SECRET_MASTER_KEY`、`AINATIVE_SWAGGER_ENABLED` 等)。

## 维护约定

- **新增接口**:写 `@GetMapping/@PostMapping` + 一行 `@Operation(summary="中文说明")`;
  控制器上若还没有 `@Tag` 就补一个领域标签。`OpenApiContractTest` 会替你检查有没有漏。
- **改接口**:不需要改任何文档文件——Swagger 由代码生成,`/v3/api-docs` 自动更新。
- **改 schema**:只改 `platform/ainative/docs/seekdb-schema-v1-*.sql`(运行库与测试库同源),
  不要在生产代码里另写 DDL。
- **加测试**:接口测试继承 `IntegrationTestBase`,用 `newUser("前缀")` 拿独立工作区;
  响应文本一律用 `body(result)` 读(UTF-8),不要用 `getContentAsString()`(默认 ISO-8859-1 会乱码)。
- **重跑整套**:`scripts/apply-openapi-tags.py` 与 `scripts/apply-openapi-operations.py`
  可用于批量补齐注解(幂等,已带注解的会跳过);新增控制器建议直接手写注解,语义更准。

## 数据

seekdb/MySQL `ainative_v2` 库,10 域 schema 见 `../docs/seekdb-schema-v1-*.sql`。
错误码沿用:`10001` 参数不合法 / `10401` 未登录或凭据错误 / `10500` 内部错误。
