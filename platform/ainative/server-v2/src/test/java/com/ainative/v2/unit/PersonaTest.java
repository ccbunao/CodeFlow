package com.ainative.v2.unit;

import com.ainative.v2.agent.Persona;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯逻辑单元测试:数字员工人设的装配(规则行 / 快照 / 会话提示词)。
 *
 * 回归背景:派发任务包的 identity.json 曾只带 name/roleCode —— SOUL.md/AGENT.md
 * 全文从未进入执行链路,干活的是"通用模型"而不是"这个员工"(需求 P3)。
 * 规则行是正文到可下发指令的唯一转换点,这里逐条钉住。
 */
class PersonaTest {

    @Test
    void rules_strip_markdown_markers_but_keep_content() {
        List<String> rules = Persona.rulesOf(
                "## 业务背景\n\n- 先判断受众\n1. 再找信息量最大的那一点\n> 引用也要去掉标记",
                "**强调**与 `代码` 属于正文,不能砍");

        assertThat(rules).containsExactly(
                "业务背景",
                "先判断受众",
                "再找信息量最大的那一点",
                "引用也要去掉标记",
                "**强调**与 `代码` 属于正文,不能砍");
    }

    @Test
    void rules_keep_year_like_prefix_untouched() {
        // "2024. 年的事" 里的 ". " 不是列表标记(dot>3),误剥会把年份与正文粘连。
        List<String> rules = Persona.rulesOf("2024. 年的事还在", null);

        assertThat(rules).containsExactly("2024. 年的事还在");
    }

    @Test
    void rules_skip_blank_lines_and_missing_sections() {
        List<String> rules = Persona.rulesOf("\n\n  \n第一行\n\n第二行\n", null);

        assertThat(rules).containsExactly("第一行", "第二行");
        assertThat(Persona.rulesOf(null, null)).isEmpty();
    }

    @Test
    void snapshot_fills_blanks_and_derives_rules() {
        Persona.Snapshot p = Persona.snapshotOf("标题优化师", "TITLE_COACH", "公众号标题优化师",
                "你是数字员工", "1. 给 5 个候选");

        assertThat(p.soulMd()).isEqualTo("你是数字员工");
        assertThat(p.agentMd()).isEqualTo("1. 给 5 个候选");
        assertThat(p.rules()).containsExactly("你是数字员工", "给 5 个候选");

        Persona.Snapshot blank = Persona.snapshotOf(null, null, null, null, null);
        assertThat(blank.toMap())
                .containsEntry("name", "")
                .containsEntry("soulMd", "")
                .containsEntry("agentMd", "")
                .containsEntry("rules", List.of());
    }

    @Test
    void snapshot_json_round_trip_keeps_persona_and_rules() throws Exception {
        Persona.Snapshot p = Persona.snapshotOf("标题优化师", "TITLE_COACH", "公众号标题优化师",
                "你是数字员工", "给 5 个候选");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p.toMap());

        Persona.Snapshot back = Persona.fromJson(json);

        assertThat(back).isNotNull();
        assertThat(back.name()).isEqualTo("标题优化师");
        assertThat(back.roleCode()).isEqualTo("TITLE_COACH");
        assertThat(back.soulMd()).isEqualTo("你是数字员工");
        assertThat(back.agentMd()).isEqualTo("给 5 个候选");
        assertThat(back.rules()).containsExactly("你是数字员工", "给 5 个候选");
    }

    @Test
    void snapshot_json_without_rules_rederives_them() {
        String json = "{\"name\":\"n\",\"roleCode\":\"c\",\"roleName\":\"r\","
                + "\"soulMd\":\"背景一句\",\"agentMd\":\"职责一句\"}";

        Persona.Snapshot back = Persona.fromJson(json);

        assertThat(back).isNotNull();
        assertThat(back.rules()).containsExactly("背景一句", "职责一句");
    }

    @Test
    void snapshot_json_absent_or_broken_returns_null_for_live_read_fallback() {
        assertThat(Persona.fromJson(null)).isNull();
        assertThat(Persona.fromJson("")).isNull();
        assertThat(Persona.fromJson("not json")).isNull();
    }

    @Test
    void system_prompt_carries_role_and_both_persona_sections() {
        String prompt = Persona.systemPrompt("公众号标题优化师", "背景正文", "职责正文");

        assertThat(prompt).contains("公众号标题优化师");
        assertThat(prompt).contains("背景正文");
        assertThat(prompt).contains("职责正文");
        assertThat(prompt).contains("SOUL.md");
        assertThat(prompt).contains("AGENT.md");
    }

    @Test
    void system_prompt_omits_absent_sections() {
        String prompt = Persona.systemPrompt("", "", "");

        assertThat(prompt).contains("数字员工");
        assertThat(prompt).doesNotContain("SOUL.md");
        assertThat(prompt).doesNotContain("AGENT.md");
    }
}
