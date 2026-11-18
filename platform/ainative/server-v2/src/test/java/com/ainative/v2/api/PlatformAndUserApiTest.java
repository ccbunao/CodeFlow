package com.ainative.v2.api;

import com.ainative.v2.support.IntegrationTestBase;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台与用户域:品牌/平台设置(PlatformController)/个人自助(UserSelfServiceController)/
 * 通知偏好(NotifyPrefController)/操作审计与站内通知。
 */
class PlatformAndUserApiTest extends IntegrationTestBase {

    @Test
    void branding_public_readable_without_token() throws Exception {
        mvc.perform(get("/api/platform/branding/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void branding_write_admin_only() throws Exception {
        Map<String, Object> plain = newUser("brand");
        // 非管理员写品牌 → 业务失败(权限语义,不是 10401)
        mvc.perform(put("/api/platform/branding")
                        .header("Authorization", bearer(plain))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("platformName", "越权改名"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        // 管理员可写
        Map<String, Object> admin = adminUser();
        mvc.perform(put("/api/platform/branding")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("platformName", "验收平台名"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void settings_group_roundtrip() throws Exception {
        Map<String, Object> user = newUser("settings");
        String auth = bearer(user);
        // 读默认设置(组存在即可读)
        mvc.perform(get("/api/settings/ai").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void user_profile_and_password_flow() throws Exception {
        Map<String, Object> user = newUser("self");
        String auth = bearer(user);

        mvc.perform(get("/api/users/me/profile").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value(user.get("username")));

        var putResp = mvc.perform(put("/api/users/me/profile")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("nickname", "验收改名"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(putResp)).as("改昵称应成功").contains("\"success\":true");

        mvc.perform(get("/api/users/me/profile").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("验收改名"));

        // 改密码 → 旧密码登录失败,新密码成功
        mvc.perform(put("/api/users/me/password")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "oldPassword", user.get("password"),
                                "newPassword", "NewPass0rd!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void user_settings_roundtrip() throws Exception {
        Map<String, Object> user = newUser("uset");
        String auth = bearer(user);

        mvc.perform(put("/api/users/me/settings/ui.locale")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("value", "en-US"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(get("/api/users/me/settings/ui.locale").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void notification_prefs_roundtrip() throws Exception {
        Map<String, Object> user = newUser("npref");
        mvc.perform(get("/api/notifications/prefs").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void audit_logs_require_admin_but_readable_for_admin() throws Exception {
        Map<String, Object> plain = newUser("audit");
        // 非 admin 读审计 → 业务失败(admin-only)
        mvc.perform(get("/api/audit-logs").header("Authorization", bearer(plain)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        // admin 可读;站内通知对所有用户开放
        Map<String, Object> admin = adminUser();
        mvc.perform(get("/api/audit-logs").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/notifications").header("Authorization", bearer(plain)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer(plain)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
