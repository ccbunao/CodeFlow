package com.ainative.integration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 外部项目绑定 DAO(v2 适配层)。
 *
 * <p>同事的 v1 实现基于 MyBatis + {@code external_project_binding} 表;
 * v2 全量走 JdbcTemplate,绑定落在 {@code ex_binding}(与禅道共用一张表)。
 * 字段映射:
 * <ul>
 *   <li>tenantId ↔ workspace_id;externalProjectId ↔ project_id;externalProjectName ↔ name</li>
 *   <li>enabled ↔ status('ACTIVE'/'DISABLED');credentialRef ↔ credential_ref(密文)</li>
 *   <li>baseUrl/clientKey/regionId/writebackStaffId/pollIntervalSeconds/lastSuccessAt/lastError
 *       ↔ config_json</li>
 * </ul>
 *
 * <p>方法签名与 v1 的 {@code ExternalProjectBindingDao} 保持一致,
 * 使 TAPD 控制器与 provider 无需改动。
 */
@Repository
public class ExternalProjectBindingDao {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExternalProjectBindingDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 写入 ──

    public void insert(ExternalProjectBindingDO binding) {
        Long existing = findByProjectId(binding.getTenantId(), binding.getProvider(), binding.getExternalProjectId());
        if (existing != null) {
            binding.setId(existing);
            updateConnection(binding);
            return;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ex_binding (workspace_id, provider, project_id, name, credential_ref, config_json, status)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, binding.getTenantId());
            ps.setString(2, binding.getProvider());
            ps.setString(3, binding.getExternalProjectId());
            ps.setString(4, binding.getExternalProjectName());
            ps.setString(5, binding.getCredentialRef());
            ps.setString(6, configJson(binding).toString());
            ps.setString(7, enabledOf(binding) ? "ACTIVE" : "DISABLED");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            binding.setId(key.longValue());
        }
    }

    public int updateConnection(ExternalProjectBindingDO binding) {
        if (binding.getId() == null) {
            return 0;
        }
        return jdbc.update("UPDATE ex_binding SET name=?, credential_ref=?, config_json=?, status=? WHERE id=?",
                binding.getExternalProjectName(), binding.getCredentialRef(), configJson(binding).toString(),
                enabledOf(binding) ? "ACTIVE" : "DISABLED", binding.getId());
    }

    public int setEnabled(long id, long tenantId, String provider, int enabled) {
        return jdbc.update("UPDATE ex_binding SET status=? WHERE id=? AND workspace_id=? AND provider=?",
                enabled == 1 ? "ACTIVE" : "DISABLED", id, tenantId, provider);
    }

    public int markSyncSuccess(Long id, Long tenantId, Date lastSuccessAt) {
        if (id == null) {
            return 0;
        }
        ObjectNode config = configJson(current(id, tenantId));
        config.put("lastSuccessAt", lastSuccessAt == null ? null : Instant.ofEpochMilli(lastSuccessAt.getTime()).toString());
        config.putNull("lastError");
        return jdbc.update("UPDATE ex_binding SET config_json=? WHERE id=?", config.toString(), id);
    }

    public int markSyncFailure(Long id, Long tenantId, String lastError) {
        if (id == null) {
            return 0;
        }
        ObjectNode config = configJson(current(id, tenantId));
        config.put("lastError", lastError == null ? "" : lastError);
        return jdbc.update("UPDATE ex_binding SET config_json=? WHERE id=?", config.toString(), id);
    }

    public int updateReconcileCursor(Long id, Long tenantId, String reconcileCursor) {
        if (id == null) {
            return 0;
        }
        ObjectNode config = configJson(current(id, tenantId));
        config.put("reconcileCursor", reconcileCursor == null ? "" : reconcileCursor);
        return jdbc.update("UPDATE ex_binding SET config_json=? WHERE id=?", config.toString(), id);
    }

    // ── 读取 ──

    public ExternalProjectBindingDO findById(Long id) {
        if (id == null) {
            return null;
        }
        List<ExternalProjectBindingDO> rows = query("SELECT * FROM ex_binding WHERE id=? AND deleted_at IS NULL", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ExternalProjectBindingDO findByProject(Long tenantId, String provider, String externalProjectId) {
        Long id = findByProjectId(tenantId, provider, externalProjectId);
        return id == null ? null : findById(id);
    }

    public List<ExternalProjectBindingDO> list(Long tenantId, String provider, int offset, int limit) {
        return query("SELECT * FROM ex_binding WHERE workspace_id=? AND provider=? AND deleted_at IS NULL"
                + " ORDER BY id DESC LIMIT ? OFFSET ?", tenantId, provider, limit, offset);
    }

    public List<ExternalProjectBindingDO> listEnabled(String provider) {
        return query("SELECT * FROM ex_binding WHERE provider=? AND status='ACTIVE' AND deleted_at IS NULL", provider);
    }

    private Long findByProjectId(Long tenantId, String provider, String externalProjectId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM ex_binding WHERE workspace_id=? AND provider=? AND project_id=? AND deleted_at IS NULL",
                Long.class, tenantId, provider, externalProjectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ExternalProjectBindingDO current(Long id, Long tenantId) {
        List<ExternalProjectBindingDO> rows = tenantId == null
                ? query("SELECT * FROM ex_binding WHERE id=? AND deleted_at IS NULL", id)
                : query("SELECT * FROM ex_binding WHERE id=? AND workspace_id=? AND deleted_at IS NULL", id, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<ExternalProjectBindingDO> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> map(rs), args);
    }

    private ExternalProjectBindingDO map(ResultSet rs) throws SQLException {
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setId(rs.getLong("id"));
        binding.setTenantId(rs.getLong("workspace_id"));
        binding.setProvider(rs.getString("provider"));
        binding.setExternalProjectId(rs.getString("project_id"));
        binding.setExternalProjectName(rs.getString("name"));
        binding.setCredentialRef(rs.getString("credential_ref"));
        binding.setEnabled("ACTIVE".equals(rs.getString("status")) ? 1 : 0);
        binding.setGmtCreate(rs.getTimestamp("gmt_create"));
        binding.setGmtModified(rs.getTimestamp("gmt_modified"));
        JsonNode config = readConfig(rs.getString("config_json"));
        binding.setBaseUrl(config.path("baseUrl").asText(null));
        binding.setClientKey(config.path("clientKey").asText(null));
        binding.setRegionId(config.path("regionId").asText(null));
        binding.setWritebackStaffId(config.path("writebackStaffId").asText(null));
        Integer poll = config.path("pollIntervalSeconds").isInt() ? config.path("pollIntervalSeconds").asInt() : null;
        binding.setPollIntervalSeconds(poll);
        binding.setLastSuccessAt(parseDate(config.path("lastSuccessAt").asText(null)));
        binding.setLastError(config.path("lastError").asText(null));
        binding.setReconcileCursor(config.path("reconcileCursor").asText(null));
        return binding;
    }

    private JsonNode readConfig(String raw) {
        try {
            JsonNode node = mapper.readTree(raw == null ? "{}" : raw);
            return node == null ? mapper.createObjectNode() : node;
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    /** 把 DO 里的扩展字段写回 config_json。 */
    private ObjectNode configJson(ExternalProjectBindingDO binding) {
        ObjectNode config = mapper.createObjectNode();
        if (binding == null) {
            return config;
        }
        put(config, "baseUrl", binding.getBaseUrl());
        put(config, "clientKey", binding.getClientKey());
        put(config, "regionId", binding.getRegionId());
        put(config, "writebackStaffId", binding.getWritebackStaffId());
        if (binding.getPollIntervalSeconds() != null) {
            config.put("pollIntervalSeconds", binding.getPollIntervalSeconds());
        }
        if (binding.getLastSuccessAt() != null) {
            config.put("lastSuccessAt", Instant.ofEpochMilli(binding.getLastSuccessAt().getTime()).toString());
        }
        put(config, "lastError", binding.getLastError());
        put(config, "reconcileCursor", binding.getReconcileCursor());
        return config;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static boolean enabledOf(ExternalProjectBindingDO binding) {
        return binding.getEnabled() == null || binding.getEnabled() == 1;
    }

    private static Date parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Date.from(Instant.parse(text));
        } catch (Exception e) {
            return null;
        }
    }

    /** 便于调试:绑定最后修改时间。 */
    public Timestamp modifiedAt(long id) {
        return jdbc.queryForObject("SELECT gmt_modified FROM ex_binding WHERE id=?", Timestamp.class, id);
    }
}
