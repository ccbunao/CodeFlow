package com.ainative.v2.ex;

import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.dto.SyncNowResult;
import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.provider.ExternalWorkitemDetail;
import com.ainative.integration.provider.ExternalWorkitemSummary;
import com.ainative.security.crypto.SecretCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TAPD 入库服务(v2 适配层):把外部工作项落成 v2 工单(wi_ticket)并建立外部链接(ex_item_link)。
 *
 * <p>对应 v1 的 {@code InboundSyncService.syncWorkitems},但 v1 写的是 v1 的 workitem 表,
 * v2 的工单模型不同(状态机/空间/标签),因此这里按 v2 表结构实现同名同签名的方法,
 * 让同事的 TAPD 控制器无需改动。
 */
@Service
public class TapdInboundSyncService {
    private static final Logger log = LoggerFactory.getLogger(TapdInboundSyncService.class);
    private static final String SOURCE = "TAPD";
    /** AES-GCM 密文信封前缀(与 AesGcmSecretCrypto 一致)。 */
    private static final String ENVELOPE_PREFIX = "enc:v1:";

    private final JdbcTemplate jdbc;
    private final SecretCrypto crypto;
    private final com.ainative.v2.ws.WorkspaceProvisioner provisioner;

    public TapdInboundSyncService(JdbcTemplate jdbc, SecretCrypto crypto,
                                  com.ainative.v2.ws.WorkspaceProvisioner provisioner) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.provisioner = provisioner;
    }

    /** 同步一批外部工作项:新条目建单,已有条目按远端最新内容刷新。 */
    public SyncNowResult syncWorkitems(ExternalProjectBindingDO binding,
                                       List<? extends ExternalWorkitemSummary> workitems,
                                       long userId) {
        SyncNowResult result = new SyncNowResult();
        if (binding == null || workitems == null) {
            return result;
        }
        long workspaceId = binding.getTenantId();
        long bindingId = binding.getId();
        for (ExternalWorkitemSummary summary : workitems) {
            String externalId = summary.getExternalId();
            if (externalId == null || externalId.isBlank()) {
                continue;
            }
            try {
                boolean created = upsertTicket(binding, externalId, summary);
                if (created) {
                    result.setImported(result.getImported() + 1);
                } else {
                    result.setUpdated(result.getUpdated() + 1);
                }
                result.getWorkitemIds().add(ticketIdOf(bindingId, externalId));
            } catch (Exception e) {
                log.warn("tapd sync item failed externalId={} error={}", externalId, e.getMessage());
            }
        }
        log.info("tapd inbound sync binding={} imported={} updated={}",
                bindingId, result.getImported(), result.getUpdated());
        return result;
    }

    private boolean upsertTicket(ExternalProjectBindingDO binding, String externalId,
                                 ExternalWorkitemSummary summary) {
        long workspaceId = binding.getTenantId();
        long bindingId = binding.getId();
        String title = summary.getTitle() == null || summary.getTitle().isBlank()
                ? "TAPD 工作项 " + externalId : summary.getTitle();
        String body = summary instanceof ExternalWorkitemDetail detail ? detail.getContentMd() : null;
        String type = summary.getWorkType() == null ? "REQ" : summary.getWorkType();
        Integer priority = summary instanceof ExternalWorkitemDetail detail ? detail.getPriority() : null;
        List<Long> linked = jdbc.queryForList(
                "SELECT ticket_id FROM ex_item_link WHERE binding_id=? AND external_id=?",
                Long.class, bindingId, externalId);
        if (!linked.isEmpty()) {
            long ticketId = linked.get(0);
            List<Map<String, Object>> current = jdbc.queryForList(
                    "SELECT title, COALESCE(body_md,'') AS body_md, type, priority FROM wi_ticket WHERE id=?", ticketId);
            if (current.isEmpty()) {
                return false;
            }
            Map<String, Object> row = current.get(0);
            int priorityValue = priority == null ? 2 : priority;
            boolean changed = !Objects.equals(row.get("title"), title)
                    || !Objects.equals(row.get("body_md"), body == null ? "" : body)
                    || !Objects.equals(row.get("type"), type)
                    || !Objects.equals(intOf(row.get("priority")), priorityValue);
            if (changed) {
                jdbc.update("UPDATE wi_ticket SET title=?, body_md=?, type=?, priority=?, version=version+1 WHERE id=?",
                        title, body == null ? "" : body, type, priorityValue, ticketId);
                jdbc.update("INSERT INTO wi_journal (workspace_id, ticket_id, event_type, actor_type, to_val)"
                                + " VALUES (?,?, 'EXTERNAL_UPDATE', 'SYSTEM', ?)",
                        workspaceId, ticketId, externalId);
            }
            return false;
        }
        Long flowId = defaultFlow(workspaceId);
        Long stateId = defaultState(workspaceId, flowId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO wi_ticket (workspace_id, title, body_md, type, priority, flow_id, state_id,"
                            + " external_source, external_id, version) VALUES (?,?,?,?,?,?,?,?,?, 0)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, workspaceId);
            ps.setString(2, title);
            ps.setString(3, body == null ? "" : body);
            ps.setString(4, type);
            ps.setInt(5, priority == null ? 2 : priority);
            if (flowId == null) ps.setNull(6, Types.BIGINT); else ps.setLong(6, flowId);
            if (stateId == null) ps.setNull(7, Types.BIGINT); else ps.setLong(7, stateId);
            ps.setString(8, SOURCE);
            ps.setString(9, externalId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long ticketId = key == null ? 0 : key.longValue();
        jdbc.update("INSERT INTO ex_item_link (workspace_id, binding_id, external_id, ticket_id) VALUES (?,?,?,?)",
                workspaceId, bindingId, externalId, ticketId);
        jdbc.update("INSERT INTO wi_journal (workspace_id, ticket_id, event_type, actor_type, to_val)"
                        + " VALUES (?,?, 'EXTERNAL_IMPORT', 'SYSTEM', ?)",
                workspaceId, ticketId, externalId);
        return true;
    }

    private Long ticketIdOf(long bindingId, String externalId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT ticket_id FROM ex_item_link WHERE binding_id=? AND external_id=?",
                Long.class, bindingId, externalId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    private Long defaultFlow(long workspaceId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM wi_flow WHERE workspace_id=? AND deleted_at IS NULL ORDER BY is_default DESC, id LIMIT 1",
                Long.class, workspaceId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        // 新工作区没有状态机:按内置模板开户,否则导入的工单无法流转
        return provisioner.ensureDefaultFlow(workspaceId);
    }

    private Long defaultState(long workspaceId, Long flowId) {
        if (flowId != null) {
            List<Long> states = jdbc.queryForList(
                    "SELECT id FROM wi_flow_state WHERE flow_id=? AND category='NEW' ORDER BY sort_no LIMIT 1",
                    Long.class, flowId);
            if (!states.isEmpty()) {
                return states.get(0);
            }
        }
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM wi_flow_state WHERE workspace_id=? AND category='NEW' ORDER BY sort_no LIMIT 1",
                Long.class, workspaceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 由绑定还原 provider 配置(密钥从 credential_ref 解密)。 */
    public ExternalPlatformConfig configOf(ExternalProjectBindingDO binding) {
        String secret = binding.getCredentialRef();
        // 只有密文信封才解密;历史明文绑定原样使用。
        // 解密失败(主密钥没配/换过)必须抛出,不能把密文当明文发出去。
        if (secret != null && secret.startsWith(ENVELOPE_PREFIX)) {
            secret = crypto.decrypt(secret);
        } else if (secret != null && !secret.isBlank()) {
            log.warn("tapd binding {} credential is not encrypted, use as-is", binding.getId());
        }
        return new ExternalPlatformConfig(binding.getBaseUrl(), binding.getClientKey(), secret,
                binding.getRegionId(), binding.getExternalProjectId());
    }

    private static Integer intOf(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    /** 便于上层读取同步结果。 */
    public java.util.Map<String, Object> asMap(SyncNowResult result) {
        return java.util.Map.of("imported", result.getImported(), "updated", result.getUpdated());
    }
}
