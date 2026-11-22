package com.ainative.v2.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数字员工人设(SOUL.md / AGENT.md)的装配与文本化。
 *
 * <p>人设是「这个员工是谁、按什么规矩干活」的可变配置:它在创建/编辑时写进
 * {@code ag_agent},在派发时被快照进 {@code rt_run.dispatch_snapshot},并随任务包下发。
 * 这里集中两处共用的规则:
 * <ul>
 *   <li>{@link #rulesOf} —— 把两段正文压成可逐条下发的规则行(去 Markdown 标记);</li>
 *   <li>{@link #systemPrompt} —— 会话路径下发给运行器的系统提示词文本。</li>
 * </ul>
 */
public final class Persona {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Persona() {
    }

    /** 派发快照:人设的不可变副本。 */
    public record Snapshot(String name, String roleCode, String roleName,
                           String soulMd, String agentMd, List<String> rules) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name == null ? "" : name);
            m.put("roleCode", roleCode == null ? "" : roleCode);
            m.put("roleName", roleName == null ? "" : roleName);
            m.put("soulMd", soulMd == null ? "" : soulMd);
            m.put("agentMd", agentMd == null ? "" : agentMd);
            m.put("rules", rules == null ? List.of() : rules);
            return m;
        }
    }

    /** 由库里的四列装配快照:规则行由两段正文推导,保证包体与库内容自洽。 */
    public static Snapshot snapshotOf(String name, String roleCode, String roleName,
                                      String soulMd, String agentMd) {
        return new Snapshot(
                name == null ? "" : name,
                roleCode == null ? "" : roleCode,
                roleName == null ? "" : roleName,
                soulMd == null ? "" : soulMd,
                agentMd == null ? "" : agentMd,
                rulesOf(soulMd, agentMd));
    }

    /** 解析快照 JSON;内容不合法时返回 null,由调用方回落到实时读取。 */
    public static Snapshot fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> m = MAPPER.readValue(json, new TypeReference<>() {
            });
            Object rules = m.get("rules");
            String soul = str(m.get("soulMd"));
            String agent = str(m.get("agentMd"));
            List<String> ruleList = rules instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            if (ruleList.isEmpty()) {
                // 快照缺 rules(老数据或手写):由两段正文重新推导,保证包体里有可执行的规则行
                ruleList = rulesOf(soul, agent);
            }
            return new Snapshot(
                    str(m.get("name")), str(m.get("roleCode")), str(m.get("roleName")),
                    soul, agent, ruleList);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 规则行 = 两段正文的非空行,去掉 Markdown 标题/列表标记后按原顺序拼接。
     *
     * <p>不去正文里的强调与代码块:那是内容本身,砍掉会改写人设语义。
     */
    public static List<String> rulesOf(String soulMd, String agentMd) {
        List<String> out = new ArrayList<>();
        for (String src : new String[]{soulMd, agentMd}) {
            if (src == null || src.isBlank()) {
                continue;
            }
            for (String line : src.split("\\r?\\n")) {
                String s = stripMarkdownMarker(line.trim());
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 去掉行首的 Markdown 标记(#{1,6} / - / * / 1. / >)与随后的空白。 */
    private static String stripMarkdownMarker(String line) {
        String s = line;
        while (true) {
            if (s.startsWith("#")) {
                int i = 0;
                while (i < s.length() && s.charAt(i) == '#') {
                    i++;
                }
                if (i <= 6 && i < s.length() && s.charAt(i) == ' ') {
                    s = s.substring(i + 1).trim();
                    continue;
                }
            }
            if (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("> ")) {
                s = s.substring(2).trim();
                continue;
            }
            int dot = s.indexOf(". ");
            if (dot > 0 && dot <= 3 && s.chars().limit(dot).allMatch(Character::isDigit)) {
                s = s.substring(dot + 2).trim();
                continue;
            }
            return s;
        }
    }

    /** 会话路径的系统提示词:身份句 + 两段正文(会话不区分规则行,保留原始结构)。 */
    public static String systemPrompt(String roleName, String soulMd, String agentMd) {
        StringBuilder sb = new StringBuilder("你是 AINative 的数字员工。");
        if (roleName != null && !roleName.isBlank()) {
            sb.append("你的角色:").append(roleName.trim()).append("。");
        }
        if (soulMd != null && !soulMd.isBlank()) {
            sb.append("\n\n【SOUL.md · 业务背景与角色定位】\n").append(soulMd.trim());
        }
        if (agentMd != null && !agentMd.isBlank()) {
            sb.append("\n\n【AGENT.md · 工作方法与输出格式】\n").append(agentMd.trim());
        }
        return sb.toString();
    }
}
