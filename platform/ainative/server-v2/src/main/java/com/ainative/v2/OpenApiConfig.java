package com.ainative.v2;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger 文档配置。
 *
 * springdoc 自动扫描全部 @RestController 生成路径、参数与返回类型——本文档类只补充
 * 平台元信息与鉴权方式,不手工罗列接口(接口以真实代码为准,文档随代码自动演进)。
 *
 * 访问:/swagger-ui.html(界面) · /v3/api-docs(OpenAPI JSON)。
 * 鉴权:除 /api/auth/register、/api/auth/login 外,业务接口一律 Bearer JWT
 * (登录/注册响应中的 accessToken)。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CodeFlow 平台 API")
                        .description("""
                        CodeFlow 从需求到交付的一站式 AI 交付平台 —— 服务端 REST API。

                        域速览:
                        - 鉴权与用户:注册/登录/me、个人设置、MCP 令牌
                        - 需求交付:工单 CRUD/状态流转/澄清会话/派发执行/交付进度/产物
                        - 执行与自动化:运行节点注册与心跳、启动命令、AI 数字员工、编队、交付流程模板
                        - 分析:项目总览、交付分析、操作审计
                        - 配置资产:代码仓库、AI 记忆、AI 技能、状态模板、环境变量、配置备份
                        - 外部连接:禅道/TAPD 绑定与回写、消息渠道、通知偏好
                        - 平台管理:品牌/平台级配置、开放平台、定时任务

                        响应包络:统一为 {success, code, message, data};业务失败 success=false 且 code 为业务错误码。
                        写操作与未登录访问在控制器层 requireUserId 校验(10401)。
                        """)
                        .version("2.0.0")
                        .contact(new Contact().name("CodeFlow 开源项目").url("https://github.com/qsumm/CodeFlow")))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录/注册返回的 accessToken;业务接口请求头 Authorization: Bearer <token>")));
    }
}
