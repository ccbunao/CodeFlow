package com.ainative.v2.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 澄清会话产物归档:每轮澄清结论落盘并登记为需求产物。
 *
 * <p>澄清会话此前只把回复写进 {@code ag_chat_turn.content_md} —— 结论散在聊天记录里,
 * 交付时既进不了需求产物区,也没法作为附件被后续澄清引用。这里把每一轮回复归档成
 * 产物({@code as_artifact.kind='CLARIFICATION'}),并用 {@code cv_artifact} 关联到
 * 对应的会话回合:cv_artifact 本就是为"会话回合的产物"设计的。
 *
 * <p>归档失败只记日志:澄清结论已经落在会话记录里,产物归档是增强,不该让整轮会话失败。
 */
@Service
public class ClarificationArchiveService {
    private static final Logger log = LoggerFactory.getLogger(ClarificationArchiveService.class);

    /** 澄清会话标题前缀:clarification:{ticketId}:{agentId} */
    private static final String TITLE_PREFIX = "clarification:";

    private final JdbcTemplate jdbc;
    private final ArtifactStorage storage;

    public ClarificationArchiveService(JdbcTemplate jdbc, ArtifactStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /**
     * 归档一轮澄清结论。
     *
     * @param chatId 会话 id
     * @param turnId 该轮 ASSISTANT 回合的 ag_chat_turn.id
     * @param seq    该轮回合序号(用于产物名)
     * @param agentId 澄清所用数字员工
     * @param replyMarkdown 本轮回复正文
     * @return 是否产生产物记录(非澄清会话返回 false)
     */
    public boolean archive(long chatId, long turnId, int seq, long agentId, String replyMarkdown) {
        try {
            List<Map<String, Object>> chats = jdbc.queryForList(
                    "SELECT workspace_id, agent_id, title FROM ag_chat WHERE id=? AND deleted_at IS NULL", chatId);
            if (chats.isEmpty()) {
                return false;
            }
            Map<String, Object> chat = chats.get(0);
            String title = chat.get("title") == null ? "" : String.valueOf(chat.get("title"));
            if (!title.startsWith(TITLE_PREFIX)) {
                // 普通对话会话不是澄清会话,没有归属需求,不归档。
                return false;
            }
            Long ticketId = ticketIdOf(title);
            if (ticketId == null) {
                log.warn("clarification archive skipped: bad title chatId={} title={}", chatId, title);
                return false;
            }
            long workspaceId = chat.get("workspace_id") instanceof Number w ? w.longValue() : 0L;
            String name = "clarification-turn-" + seq + ".md";
            String content = document(ticketId, agentId, seq, replyMarkdown);

            ArtifactStorage.Stored stored =
                    storage.storeIn("conversations/" + chatId, name, content.getBytes(StandardCharsets.UTF_8));

            // 同名产物重投:先删旧行(含其 cv_artifact 关联),再插新的,保证重投不产生重复。
            List<Long> stale = jdbc.queryForList(
                    "SELECT id FROM as_artifact WHERE ticket_id=? AND name=? AND kind='CLARIFICATION'"
                            + " AND deleted_at IS NULL", Long.class, ticketId, name);
            for (Long old : stale) {
                jdbc.update("DELETE FROM cv_artifact WHERE artifact_id=?", old);
                jdbc.update("DELETE FROM as_artifact WHERE id=?", old);
            }

            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO as_artifact (workspace_id, ticket_id, run_id, name, kind, object_key, size_bytes)"
                                + " VALUES (?,?,NULL,?, 'CLARIFICATION', ?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, workspaceId);
                ps.setLong(2, ticketId);
                ps.setString(3, name);
                ps.setString(4, stored.objectKey());
                ps.setLong(5, stored.sizeBytes());
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                log.warn("clarification archive skipped: no artifact id chatId={} seq={}", chatId, seq);
                return false;
            }
            long artifactId = key.longValue();
            // cv_artifact 的唯一键是 (turn_id, artifact_id),重投时先清掉本回合的旧关联。
            jdbc.update("DELETE FROM cv_artifact WHERE turn_id=?", turnId);
            jdbc.update("INSERT INTO cv_artifact (workspace_id, turn_id, artifact_id) VALUES (?,?,?)",
                    workspaceId, turnId, artifactId);
            log.info("clarification archived ticketId={} chatId={} turnId={} artifactId={} bytes={}",
                    ticketId, chatId, turnId, artifactId, stored.sizeBytes());
            return true;
        } catch (Exception e) {
            // 归档是增强路径:失败不打断会话,记日志即可。
            log.warn("clarification archive failed chatId={} seq={} error={}", chatId, seq, e.getMessage());
            return false;
        }
    }

    private Long ticketIdOf(String title) {
        try {
            String[] parts = title.split(":");
            if (parts.length < 2) {
                return null;
            }
            return Long.parseLong(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 归档正文:结论原文 + 可追溯的上下文头。 */
    private String document(long ticketId, long agentId, int seq, String replyMarkdown) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 澄清纪要 · 第 ").append(seq).append(" 轮\n\n");
        sb.append("- 需求:").append(ticketId).append('\n');
        sb.append("- 数字员工:").append(agentId).append('\n');
        sb.append("- 归档时间:").append(LocalDateTime.now()).append("\n\n");
        sb.append("## 澄清结论\n\n");
        sb.append(replyMarkdown == null || replyMarkdown.isBlank() ? "(本轮无回复正文)" : replyMarkdown.trim())
                .append('\n');
        return sb.toString();
    }
}
