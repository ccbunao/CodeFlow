package com.ainative.v2.unit;

import com.ainative.v2.agent.AgentService;
import com.ainative.v2.artifact.ClarificationArchiveService;
import com.ainative.v2.cv.ConversationTurnService;
import com.ainative.v2.daemon.ExecutorHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重放积压回合按「共享节点池」语义的回归测试。
 *
 * <p>回归背景:UI 走查实测 —— 会话绑定种子数字员工「需求澄清师」(agent 2),而
 * daemon 注册在 agent 1 名下。上线补投按 <code>c.agent_id=执行器的 agent</code>
 * 过滤 → 其他数字员工的 PENDING 回合永远不重投;且旧实现用执行器的 agentId
 * 装帧,重放跨智能体会话会把 MCP 令牌和 systemPrompt 装错人。修复:重放查
 * 全部 PENDING(不限 agent),帧里的 agentId 取会话自己的。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationReplaySharedPoolTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private ExecutorHub hub;
    @Mock
    private ClarificationArchiveService archive;
    @Mock
    private AgentService agentService;

    private ConversationTurnService newService() {
        when(jdbc.<Map<String, Object>>queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("role_name", "需求澄清师", "soul_md", "soul", "agent_md", "agent")));
        when(jdbc.<Long>queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(1L));
        when(agentService.createMcpToken(anyLong(), anyString())).thenReturn(Map.of("token", "tok"));
        return new ConversationTurnService(jdbc, hub, archive, agentService);
    }

    @Test
    void replayOfCrossAgentChatUsesChatOwnAgentId() {
        // PENDING 回合属于 agent 2 的会话,执行器注册在 agent 1 名下:
        // 重放仍要投递,且帧里 agentId=2(会话自己的),不是执行器的 1。
        when(jdbc.<Map<String, Object>>queryForList(anyString())).thenReturn(List.of(Map.of(
                "chat_id", 3L, "seq", 1, "content_md", "UI 走查", "agent_id", 2L)));
        when(hub.send(eq(9L), any())).thenReturn(true);

        int sent = newService().replayPending(9L);

        assertThat(sent).isEqualTo(1);
        ArgumentCaptor<Map<String, Object>> frame = ArgumentCaptor.forClass(Map.class);
        verify(hub).send(eq(9L), frame.capture());
        assertThat(frame.getValue().get("agentId")).isEqualTo(2L);
        assertThat(frame.getValue().get("conversationId")).isEqualTo(3L);
    }

    @Test
    void replayStillReplaysWhenExecutorRegisteredUnderDifferentAgent() {
        // 旧行为对拍:会话 agent_id 与执行器名下 agent 不同 → 旧实现 0 条,新实现照常投递
        when(jdbc.<Map<String, Object>>queryForList(anyString())).thenReturn(List.of(
                Map.of("chat_id", 1L, "seq", 1, "content_md", "a", "agent_id", 1L),
                Map.of("chat_id", 3L, "seq", 1, "content_md", "b", "agent_id", 2L)));
        when(hub.send(anyLong(), any())).thenReturn(true);

        int sent = newService().replayPending(2L);

        assertThat(sent).isEqualTo(2);
    }

    @Test
    void replaySendFailureStopsButKeepsEarlierDeliveries() {
        when(jdbc.<Map<String, Object>>queryForList(anyString())).thenReturn(List.of(
                Map.of("chat_id", 1L, "seq", 1, "content_md", "a", "agent_id", 1L),
                Map.of("chat_id", 3L, "seq", 1, "content_md", "b", "agent_id", 2L)));
        when(hub.send(anyLong(), any())).thenReturn(false);

        assertThat(newService().replayPending(9L)).isZero();
    }
}
