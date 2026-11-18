package com.ainative.v2.api;

import com.ainative.v2.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI/Swagger 文档验收:/v3/api-docs 必须真实反映全部 40 个 Controller、430 个端点。
 *
 * 锁死两条底线:
 * 1) 文档可访问且 paths 覆盖核心域路由(以代码扫描出的真实路由为准);
 * 2) 文档里的 path 数量随代码增长自动增长——这里断言下限,防止接口被写出来但文档扫不到。
 */
class OpenApiDocsTest extends IntegrationTestBase {

    @Test
    void openapi_json_is_served_with_all_core_domains() throws Exception {
        var docs = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = JSON.readTree(body(docs));
        JsonNode paths = root.path("paths");

        // 核心域路径(来自 Controller 代码扫描,真实路由)
        String[] mustHave = {
                "/api/auth/register", "/api/auth/login", "/api/auth/me",
                "/api/workitems", "/api/workitems/{id}",
                "/api/workitems/{id}/transition",
                "/api/workitems/{workitemId}/clarification",
                "/api/workitems/{workitemId}/delivery-progress",
                "/api/dispatches",
                "/api/status-templates",
                "/api/sdlcs", "/api/sdlcs/page",
                "/api/squads",
                "/api/agents",
                "/api/executors", "/api/executors/{id}/launch-command",
                "/api/repos",
                "/api/memories",
                "/api/skills",
                "/api/mcp/tokens",
                "/api/integrations/{provider}/bindings",
                "/api/dashboard/overview", "/api/dashboard/pipeline-health",
                "/api/platform/branding", "/api/platform/branding/public",
                "/api/settings/{group}",
                "/api/audit-logs",
                "/api/notifications",
                "/api/users/me/profile",
                "/api/notifications/prefs",
                "/api/workspaces/mine",
                "/api/scheduled-tasks/{id}/run-now",
                "/api/scheduled-task-runs",
        };
        var missing = new java.util.ArrayList<String>();
        for (String p : mustHave) {
            if (!paths.has(p)) missing.add(p);
        }
        assertThat(missing).as("OpenAPI 文档缺失的核心路由").isEmpty();

        // 数量下限:430 个端点对应约 200+ 条 path(路径去重后);低于 150 说明扫描坏了
        int size = paths.size();
        assertThat(size).as("OpenAPI paths 总数(实际 " + size + ")").isGreaterThan(150);

        // 安全方案:bearerAuth 已声明
        assertThat(root.path("components").path("securitySchemes").has("bearerAuth")).isTrue();

        // 元信息
        assertThat(root.path("info").path("title").asText()).isEqualTo("CodeFlow 平台 API");
    }

    @Test
    void swagger_ui_is_served() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
