package com.ainative.v2.dispatch;

import com.ainative.v2.agent.Persona;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 派发快照:重建任务包所需的全部输入(人设 + 工单 + SDLC 步骤 + 名册)。
 *
 * <p>任务包的 md5 在 TASK_DISPATCH 帧里下发,运行器下载时按 md5 校验 —— 包体必须
 * 任意时刻可复现。这些输入里,人设与名册会被人在派发后改动,工单正文与 SDLC 步骤
 * 也会被修订;任何一项实时读取,重建出的字节就与派发时不同,下载直接失败。
 * 派发时把输入整体冻结进 rt_run.dispatch_snapshot,重建只读快照。
 */
public record DispatchSnapshot(Persona.Snapshot persona, String ticketTitle, String ticketBodyMd,
                               List<Map<String, Object>> steps, List<Map<String, Object>> roster) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("persona", persona == null ? Persona.snapshotOf("", "", "", "", "").toMap() : persona.toMap());
        m.put("ticket", Map.of(
                "title", ticketTitle == null ? "" : ticketTitle,
                "bodyMd", ticketBodyMd == null ? "" : ticketBodyMd));
        m.put("steps", steps == null ? List.of() : steps);
        m.put("roster", roster == null ? List.of() : roster);
        return m;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(toMap());
        } catch (Exception e) {
            throw new IllegalStateException("dispatch snapshot serialize failed", e);
        }
    }

    /** 解析快照;老数据(无快照/损坏)返回 null,由调用方回落到实时读取。 */
    public static DispatchSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> m = MAPPER.readValue(json, new TypeReference<>() {
            });
            Map<String, Object> ticket = mapOf(m.get("ticket"));
            Persona.Snapshot persona = Persona.fromJson(
                    MAPPER.writeValueAsString(m.get("persona") == null ? Map.of() : m.get("persona")));
            return new DispatchSnapshot(
                    persona,
                    str(ticket.get("title")),
                    str(ticket.get("bodyMd")),
                    listOfMaps(m.get("steps")),
                    listOfMaps(m.get("roster")));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object v) {
        return v instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object v) {
        if (!(v instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> mm) {
                out.add((Map<String, Object>) mm);
            }
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
