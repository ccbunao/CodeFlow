package com.ainative.v2.unit;

import com.ainative.v2.agent.AgentService;
import com.ainative.v2.artifact.ClarificationArchiveService;
import com.ainative.v2.cv.ConversationTurnService;
import com.ainative.v2.daemon.ExecutorHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 澄清会话下发帧必须带 mcpToken 的回归测试。
 *
 * <p>回归背景:服务端发 CONVERSATION_TURN 时没放 mcpToken,运行器端
 * turn.get("mcp_token") 为空 → 跳过 MCP 注册 → 数字员工的 claude 会话里
 * 没有 ainative MCP 工具 → 读不了需求,狂调工具直到 max_turns 报错。
 *
 * <p>令牌策略:同一会话(chatId)复用同一枚 clarify 令牌(内存缓存明文,
 * 库里只有 sha256);服务重启丢缓存后按名字约定撤销旧令牌重签,令牌表
 * 不会无限膨胀;会话删除时撤销。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationTurnMcpTokenTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private ExecutorHub hub;
    @Mock
    private ClarificationArchiveService archive;
    @Mock
    private AgentService agentService;

    private ConversationTurnService newService() {
        // systemPrompt 与 chatUserId 都走 jdbc 查询:按重载分开打桩
        when(jdbc.<Map<String, Object>>queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("role_name", "需求澄清师", "soul_md", "soul", "agent_md", "agent")));
        when(jdbc.<Long>queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(7L));
        return new ConversationTurnService(jdbc, hub, archive, agentService);
    }

    @Test
    void frame_carries_mcp_token_issued_per_chat() {
        ConversationTurnService service = newService();
        when(agentService.createMcpToken(7L, "clarify-1"))
                .thenReturn(Map.of("id", 11L, "name", "clarify-1", "token", "mcp_tok1"));

        Map<String, Object> frame = service.conversationFrame(1, 1, 1, "请读需求", 7L);

        assertThat(frame.get("type")).isEqualTo("CONVERSATION_TURN");
        assertThat(frame.get("mcpToken")).isEqualTo("mcp_tok1");
        verify(agentService).createMcpToken(7L, "clarify-1");
    }

    @Test
    void same_chat_reuses_token_without_reissue() {
        ConversationTurnService service = newService();
        when(agentService.createMcpToken(7L, "clarify-1"))
                .thenReturn(Map.of("id", 11L, "name", "clarify-1", "token", "mcp_tok1"));

        Map<String, Object> first = service.conversationFrame(1, 1, 1, "第一条", 7L);
        Map<String, Object> second = service.conversationFrame(1, 2, 1, "第二条", 7L);

        assertThat(first.get("mcpToken")).isEqualTo("mcp_tok1");
        assertThat(second.get("mcpToken")).isEqualTo("mcp_tok1");
        verify(agentService, times(1)).createMcpToken(anyLong(), anyString());
    }

    @Test
    void different_chats_get_different_tokens() {
        ConversationTurnService service = newService();
        when(agentService.createMcpToken(eq(7L), eq("clarify-1")))
                .thenReturn(Map.of("id", 11L, "name", "clarify-1", "token", "mcp_tok1"));
        when(agentService.createMcpToken(eq(7L), eq("clarify-2")))
                .thenReturn(Map.of("id", 12L, "name", "clarify-2", "token", "mcp_tok2"));

        assertThat(service.conversationFrame(1, 1, 1, "a", 7L).get("mcpToken")).isEqualTo("mcp_tok1");
        assertThat(service.conversationFrame(2, 1, 1, "b", 7L).get("mcpToken")).isEqualTo("mcp_tok2");
        verify(agentService, times(2)).createMcpToken(anyLong(), anyString());
    }

    @Test
    void after_restart_stale_named_token_is_revoked_then_reissued() {
        // 重启后内存缓存为空:同名令牌先撤销再重签,令牌表里每个会话至多一枚活跃 clarify 令牌
        ConversationTurnService service = newService();
        when(agentService.listMcpTokens(7L)).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of("id", 11L, "name", "clarify-1"))));
        when(agentService.createMcpToken(7L, "clarify-1"))
                .thenReturn(Map.of("id", 12L, "name", "clarify-1", "token", "mcp_tok2"));

        Map<String, Object> frame = service.conversationFrame(1, 1, 1, "重启后的第一条", 7L);

        assertThat(frame.get("mcpToken")).isEqualTo("mcp_tok2");
        verify(agentService).revokeMcpToken(11L);
        verify(agentService).createMcpToken(7L, "clarify-1");
    }

    @Test
    void token_issue_failure_does_not_block_the_turn() {
        ConversationTurnService service = newService();
        when(agentService.createMcpToken(anyLong(), anyString()))
                .thenThrow(new RuntimeException("sign failed"));

        Map<String, Object> frame = service.conversationFrame(1, 1, 1, "hi", 7L);

        assertThat(frame.get("type")).isEqualTo("CONVERSATION_TURN");
        assertThat(frame.get("mcpToken")).isNull();
    }

    @Test
    void delete_conversation_revokes_its_token() {
        ConversationTurnService service = newService();
        // 首轮:无同名令牌 → 直接签发
        when(agentService.listMcpTokens(7L)).thenReturn(List.of());
        when(agentService.createMcpToken(7L, "clarify-1"))
                .thenReturn(Map.of("id", 11L, "name", "clarify-1", "token", "mcp_tok1"));
        service.conversationFrame(1, 1, 1, "hi", 7L);

        // 会话结束:撤销该会话的令牌
        when(agentService.listMcpTokens(7L)).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of("id", 11L, "name", "clarify-1"))));
        service.revokeClarifyToken(1L);

        verify(agentService).revokeMcpToken(11L);
        // 撤销后再发帧:重新签发,而不是拿已撤销的明文
        when(agentService.listMcpTokens(7L)).thenReturn(List.of());
        when(agentService.createMcpToken(7L, "clarify-1"))
                .thenReturn(Map.of("id", 12L, "name", "clarify-1", "token", "mcp_tok2"));
        assertThat(service.conversationFrame(1, 2, 1, "hi", 7L).get("mcpToken")).isEqualTo("mcp_tok2");
        verify(agentService, times(2)).createMcpToken(7L, "clarify-1");
    }

    @Test
    void replay_pending_frame_also_carries_token_via_chat_user_lookup() {
        // 重放链路没有 RequestContext,userId 从 ag_chat.user_id 兜底解析
        ConversationTurnService service = newService();
        when(agentService.createMcpToken(7L, "clarify-1"))
                .thenReturn(Map.of("id", 11L, "name", "clarify-1", "token", "mcp_tok1"));

        Map<String, Object> frame = service.conversationFrame(1, 1, 1, "重放", 0L);

        assertThat(frame.get("mcpToken")).isEqualTo("mcp_tok1");
        verify(agentService, never()).createMcpToken(eq(0L), anyString());
    }
}
