package com.ainative.v2.api;

import com.ainative.v2.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 鉴权与工作区域:/api/auth/*、/api/workspaces。 */
class AuthAndWorkspaceApiTest extends IntegrationTestBase {

    @Test
    void register_login_me_flow() throws Exception {
        // 未登录 me → 业务 10401
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10401"));

        Map<String, Object> user = newUser("authflow");

        // me → 带令牌返回身份
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value(user.get("username")));

        // 错误密码 → 10401
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user.get("username") + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10401"));

        // 重复注册 → 10002
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user.get("username") + "\",\"password\":\"abc12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10002"));
    }

    @Test
    void workspaces_list_and_select() throws Exception {
        Map<String, Object> user = newUser("ws");
        var list = mvc.perform(get("/api/workspaces/mine").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        var data = dataOf(body(list));
        assertThat(data.isArray() || data.has("list") || data.has("my")).as("工作区列表结构").isTrue();
    }

    @Test
    void workspaces_requires_token() throws Exception {
        mvc.perform(get("/api/workspaces/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("10401"));
    }
}
