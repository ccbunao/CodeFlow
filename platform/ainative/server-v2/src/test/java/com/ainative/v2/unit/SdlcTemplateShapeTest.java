package com.ainative.v2.unit;

import com.ainative.v2.squad.SdlcController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯逻辑单元测试:交付流程列表契约整形(templateShape)。
 *
 * 回归背景:该方法曾用不存在的 "steps" key 覆盖掉 service 已算好的 stepCount,
 * 导致列表里「四步交付流程」显示成「0 步」——数字自相矛盾,客户可见。
 * 这里不依赖数据库,直接验证整形规则。
 */
class SdlcTemplateShapeTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shape(Map<String, Object> src) throws Exception {
        Method m = SdlcController.class.getDeclaredMethod("templateShape", Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(null, src);
    }

    @Test
    void keeps_service_step_count_when_steps_list_absent() throws Exception {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("id", 1);
        src.put("name", "标准四步交付流程");
        src.put("status", "ACTIVE");
        src.put("description", "分析与分支准备 → 编码与测试 → 验证与交付 → 交接评审");
        src.put("stepCount", 4);   // service 层算好的

        Map<String, Object> out = shape(src);

        assertThat(out.get("stepCount")).as("不得被覆盖为 0").isEqualTo(4);
        assertThat(out.get("status")).as("ACTIVE 映射为 ENABLED").isEqualTo("ENABLED");
        assertThat(out.get("description")).isEqualTo("分析与分支准备 → 编码与测试 → 验证与交付 → 交接评审");
        assertThat(out.get("workType")).isNull();
        assertThat(out.get("isDefault")).isEqualTo(0);
        assertThat(out.get("entryStepId")).isNull();
        assertThat(out.get("squadIds")).isNull();
        assertThat(out.get("squadNames")).isNull();
    }

    @Test
    void uses_steps_list_size_when_present() throws Exception {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("id", 2);
        src.put("name", "带步骤的流程");
        src.put("status", "DRAFT");
        src.put("description", null);
        src.put("steps", List.of("a", "b", "c"));

        Map<String, Object> out = shape(src);

        assertThat(out.get("stepCount")).isEqualTo(3);
        assertThat(out.get("status")).as("非 ACTIVE 保持原值").isEqualTo("DRAFT");
        assertThat(out.get("description")).as("null 描述归一为空串").isEqualTo("");
    }

    @Test
    void zero_only_when_there_is_nothing_to_count() throws Exception {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("id", 3);
        src.put("name", "空流程");
        src.put("status", "ACTIVE");
        src.put("description", "");
        // 既没有 steps 列表也没有 stepCount —— 此时置 0 才是对的

        Map<String, Object> out = shape(src);

        assertThat(out.get("stepCount")).isEqualTo(0);
    }
}
