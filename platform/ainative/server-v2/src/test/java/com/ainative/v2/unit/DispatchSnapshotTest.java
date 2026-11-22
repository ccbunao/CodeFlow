package com.ainative.v2.unit;

import com.ainative.v2.agent.Persona;
import com.ainative.v2.dispatch.DispatchSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯逻辑单元测试:派发快照(重建任务包所需的全部输入)。
 *
 * 回归背景:任务包的 md5 在派发帧里下发,运行器下载时按 md5 校验。工单正文、
 * SDLC 步骤、名册与人设都是会被人在派发后改动的东西 —— 重建时实时读,包体字节
 * 就会与派发时不同,下载直接失败(需求 FR-04)。快照就是把输入整体冻结。
 */
class DispatchSnapshotTest {

    private static DispatchSnapshot sample() {
        return new DispatchSnapshot(
                Persona.snapshotOf("标题优化师", "TITLE_COACH", "公众号标题优化师", "背景正文", "职责正文"),
                "工单标题",
                "工单正文",
                List.of(Map.of("stepId", "1", "stepOrder", 1, "name", "分析与分支准备", "goal", "确认边界")),
                List.of(Map.of("agentId", 7, "name", "标题优化师")));
    }

    @Test
    void snapshot_keeps_all_four_sections() {
        Map<String, Object> m = sample().toMap();

        assertThat(m).containsKeys("persona", "ticket", "steps", "roster");
        assertThat(m.get("ticket")).isEqualTo(Map.of("title", "工单标题", "bodyMd", "工单正文"));
    }

    @Test
    void snapshot_round_trip_preserves_everything_rebuild_needs() {
        DispatchSnapshot back = DispatchSnapshot.fromJson(sample().toJson());

        assertThat(back).isNotNull();
        assertThat(back.ticketTitle()).isEqualTo("工单标题");
        assertThat(back.ticketBodyMd()).isEqualTo("工单正文");
        assertThat(back.steps()).hasSize(1);
        assertThat(back.steps().get(0)).containsEntry("stepId", "1");
        assertThat(back.roster()).hasSize(1);
        assertThat(back.roster().get(0)).containsEntry("name", "标题优化师");
        assertThat(back.persona()).isNotNull();
        assertThat(back.persona().soulMd()).isEqualTo("背景正文");
        assertThat(back.persona().agentMd()).isEqualTo("职责正文");
        assertThat(back.persona().rules()).containsExactly("背景正文", "职责正文");
    }

    @Test
    void snapshot_absent_or_broken_returns_null_for_live_read_fallback() {
        assertThat(DispatchSnapshot.fromJson(null)).isNull();
        assertThat(DispatchSnapshot.fromJson("")).isNull();
        assertThat(DispatchSnapshot.fromJson("not json")).isNull();
    }

    @Test
    void snapshot_with_null_parts_degrades_to_empty_values() {
        DispatchSnapshot empty = new DispatchSnapshot(null, null, null, null, null);

        assertThat(empty.toJson()).contains("persona", "ticket", "steps", "roster");
        assertThat(DispatchSnapshot.fromJson(empty.toJson())).isNotNull();
    }
}
