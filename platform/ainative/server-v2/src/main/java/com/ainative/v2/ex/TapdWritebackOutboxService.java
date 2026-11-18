package com.ainative.v2.ex;

import com.ainative.integration.common.IntegrationOutboxDO;
import com.ainative.integration.receipt.ExternalOperationReadbackHandler;
import com.ainative.integration.tapd.TapdCommentReadback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 回写结果读回:同事 v1 的 TapdCommentReadback 在 v2 里的宿主。
 *
 * <p>TAPD 回写若返回 5xx(结果未知,可能已落库),不能盲目重发 ——
 * 记一条 rt_outbox,这里周期读回确认:备注里带 marker,读回命中即 DONE。
 */
@Service
public class TapdWritebackOutboxService {
    private static final Logger log = LoggerFactory.getLogger(TapdWritebackOutboxService.class);
    private static final int MAX_RETRY = 10;

    private final JdbcTemplate jdbc;
    private final TapdCommentReadback readback;

    public TapdWritebackOutboxService(JdbcTemplate jdbc, TapdCommentReadback readback) {
        this.jdbc = jdbc;
        this.readback = readback;
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT id, workspace_id, aggregate_id, payload_json, retry_count FROM rt_outbox"
                            + " WHERE event_type='COMMENT_CREATE' AND status='PENDING' AND deleted_at IS NULL"
                            + " ORDER BY id LIMIT 20");
        } catch (Exception e) {
            return;
        }
        for (Map<String, Object> row : rows) {
            try {
                long id = ((Number) row.get("id")).longValue();
                long workspaceId = ((Number) row.get("workspace_id")).longValue();
                long bindingId = ((Number) row.get("aggregate_id")).longValue();
                int retry = row.get("retry_count") == null ? 0 : ((Number) row.get("retry_count")).intValue();
                IntegrationOutboxDO receipt = new IntegrationOutboxDO();
                receipt.setId(id);
                receipt.setTenantId(workspaceId);
                receipt.setProvider("TAPD");
                receipt.setBindingId(bindingId);
                receipt.setEventType("COMMENT_CREATE");
                receipt.setPayloadJson(String.valueOf(row.get("payload_json")));
                receipt.setStatus("PENDING");
                receipt.setRetryCount(retry);
                ExternalOperationReadbackHandler.ReadbackResult result = readback.readback(receipt);
                switch (result.outcome()) {
                    case FOUND -> {
                        jdbc.update("UPDATE rt_outbox SET status='DONE', retry_count=? WHERE id=?", retry + 1, id);
                        log.info("tapd writeback confirmed outbox={}", id);
                    }
                    case DEFINITELY_NOT_FOUND -> {
                        jdbc.update("UPDATE rt_outbox SET status='FAILED', retry_count=? WHERE id=?", retry + 1, id);
                        log.warn("tapd writeback missing outbox={}", id);
                    }
                    default -> {
                        if (retry + 1 >= MAX_RETRY) {
                            jdbc.update("UPDATE rt_outbox SET status='FAILED', retry_count=? WHERE id=?", retry + 1, id);
                        } else {
                            jdbc.update("UPDATE rt_outbox SET retry_count=? WHERE id=?", retry + 1, id);
                        }
                        log.info("tapd writeback pending outbox={} detail={}", id, result.detail());
                    }
                }
            } catch (Exception e) {
                log.warn("tapd writeback readback failed error={}", e.getMessage());
            }
        }
    }
}
