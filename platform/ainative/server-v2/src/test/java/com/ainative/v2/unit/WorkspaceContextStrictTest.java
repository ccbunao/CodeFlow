package com.ainative.v2.unit;

import com.ainative.v2.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工作区上下文的强约束:后台线程不得静默回退到默认工作区。
 *
 * <p>回归背景(2026-11-23):澄清回合的落库在工作节点 WS 线程里执行,那里没有请求
 * 上下文,RequestContext 回退到 1 —— 非默认项目的 AI 回复、用量、事件全挂到 1 号
 * 项目名下(实测:项目 2 的会话,其 AI 回复回合 workspace_id=1)。此前 15 处硬编码
 * 是同一类错误的显性版本。
 */
class WorkspaceContextStrictTest {

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void background_thread_without_workspace_fails_loudly() {
        // 后台线程(未标记请求边界):没有工作区可用 → 直接报错,不再静默写成 1
        assertThatThrownBy(RequestContext::workspaceId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工作区缺失");
    }

    @Test
    void anonymous_request_still_falls_back_to_single_workspace() {
        // 请求内但令牌没带工作区(登录页、健康检查):兼容单工作区部署
        RequestContext.markInRequest();
        assertThat(RequestContext.workspaceId()).isEqualTo(1L);
        assertThat(RequestContext.inRequest()).isTrue();
    }

    @Test
    void request_with_workspace_uses_token_value() {
        RequestContext.markInRequest();
        RequestContext.setWorkspaceId(42L);
        assertThat(RequestContext.workspaceId()).isEqualTo(42L);
    }

}
