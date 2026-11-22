package com.ainative.v2.unit;

import com.ainative.v2.cv.ConversationTurnService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.LongPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 选节点加固的回归测试。
 *
 * <p>回归背景:桌面侧会自动拉起低 id 的旧 daemon,其进程环境缺 ANTHROPIC 鉴权且注入
 * ZCode 脏变量,接到澄清回合只会回复 "Not logged in"。服务端按 id 升序选节点会优先
 * 命中它。加固:daemon 启动自检后随心跳上报 healthy,选节点优先健康执行器;
 * 老 daemon 不上报该标志 → 视作不健康,仅当无任何健康节点在线时才回退旧逻辑。
 */
class ConversationTurnExecutorPickTest {

    private static final LongPredicate ONLINE = id -> id == 1L || id == 3L || id == 5L;
    // 等价于 ExecutorHub.isHealthy:未登记标志的一律 false
    private static final LongPredicate NONE_HEALTHY = id -> false;
    private static final LongPredicate THREE_HEALTHY = id -> id == 3L;

    @Test
    void healthyNodeWinsOverLowerId() {
        // id 升序 [1,3,5]:1 在线但不健康,3 在线且健康 → 必须选 3,不回退到 1
        Long picked = ConversationTurnService.pickExecutor(List.of(1L, 3L, 5L), ONLINE, THREE_HEALTHY);
        assertThat(picked).isEqualTo(3L);
    }

    @Test
    void unhealthyNodesFallBackToLowestOnlineId() {
        // 无任何健康节点:回退旧逻辑,取第一个在线者(id 升序)
        Long picked = ConversationTurnService.pickExecutor(List.of(1L, 3L, 5L), ONLINE, NONE_HEALTHY);
        assertThat(picked).isEqualTo(1L);
    }

    @Test
    void offlineHealthyNodeIsSkipped() {
        // 3 健康但不在线 → 不得选中;回退到在线但不健康的 1
        LongPredicate only1and5Online = id -> id == 1L || id == 5L;
        Long picked = ConversationTurnService.pickExecutor(List.of(1L, 3L), only1and5Online, THREE_HEALTHY);
        assertThat(picked).isEqualTo(1L);
    }

    @Test
    void noneOnlineReturnsNull() {
        LongPredicate noneOnline = id -> false;
        assertThat(ConversationTurnService.pickExecutor(List.of(1L, 3L), noneOnline, THREE_HEALTHY)).isNull();
    }

    @Test
    void emptyCandidatesReturnsNull() {
        assertThat(ConversationTurnService.pickExecutor(List.of(), ONLINE, THREE_HEALTHY)).isNull();
    }

    // ---- mergeExecutorCandidates:共享节点池(UI 走查实测的 QUEUED 缺陷) ----
    // 回归背景:daemon 注册在 agent 1 名下,会话却绑定「需求澄清师」(agent 2);
    // 按 agent_id 严格过滤 → conversation turn queued (no online executor),回合永远无人投递。

    @Test
    void agentBoundExecutorComesFirst() {
        List<Long> merged = ConversationTurnService.mergeExecutorCandidates(List.of(2L), List.of(1L, 2L, 3L));
        assertThat(merged).containsExactly(2L, 1L, 3L);
    }

    @Test
    void sharedPoolFallbackWhenNoBoundExecutor() {
        List<Long> merged = ConversationTurnService.mergeExecutorCandidates(List.of(), List.of(1L, 3L));
        assertThat(merged).containsExactly(1L, 3L);
    }

    @Test
    void mergeDeduplicatesKeepingBoundFirst() {
        List<Long> merged = ConversationTurnService.mergeExecutorCandidates(List.of(1L), List.of(1L, 3L));
        assertThat(merged).containsExactly(1L, 3L);
    }

    @Test
    void boundExecutorWinsOverSharedEvenWhenSharedIsHealthierLooking() {
        // 绑定节点 [2] 在线但不健康、共享节点 [1] 健康:merge 保证绑定优先 → 命中 2
        // (健康只是偏好不是硬门槛);绑定节点离线时,回退到健康的共享节点 1。
        LongPredicate allOnline = id -> true;
        assertThat(ConversationTurnService.pickExecutor(
                ConversationTurnService.mergeExecutorCandidates(List.of(2L), List.of(1L)), allOnline, NONE_HEALTHY))
                .isEqualTo(2L);
        LongPredicate onlyShared1Online = id -> id == 1L;
        assertThat(ConversationTurnService.pickExecutor(
                ConversationTurnService.mergeExecutorCandidates(List.of(2L), List.of(1L)), onlyShared1Online, THREE_HEALTHY))
                .isEqualTo(1L);
    }
}
