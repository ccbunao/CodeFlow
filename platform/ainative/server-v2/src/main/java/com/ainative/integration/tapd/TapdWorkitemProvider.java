package com.ainative.integration.tapd;

import com.ainative.integration.provider.ExternalComment;
import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.provider.ExternalPlatformException;
import com.ainative.integration.provider.ExternalPrincipalRef;
import com.ainative.integration.provider.ExternalStatusOption;
import com.ainative.integration.provider.ExternalWorkitemDetail;
import com.ainative.integration.provider.ExternalWorkitemProvider;
import com.ainative.integration.provider.ExternalWorkitemSummary;
import com.ainative.integration.provider.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TapdWorkitemProvider implements ExternalWorkitemProvider {
    public static final String PROVIDER = "TAPD";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final ZoneId TAPD_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TAPD_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final String WORKITEM_FIELDS = "id,workspace_id,description,status,priority,created,modified,";
    private static final String COMMENT_MARKER = "an-op:";
    private final TapdClient client;

    public TapdWorkitemProvider(TapdClient client) {
        this.client = client;
    }

    private enum Kind {
        STORY("stories", "Story", "name", "creator", "owner", "REQ", "story"),
        BUG("bugs", "Bug", "title", "reporter", "current_owner", "BUG", "bug");

        final String endpoint;
        final String wrapper;
        final String title;
        final String reporter;
        final String owner;
        final String workType;
        final String webType;

        Kind(String endpoint, String wrapper, String title, String reporter, String owner,
             String workType, String webType) {
            this.endpoint = endpoint;
            this.wrapper = wrapper;
            this.title = title;
            this.reporter = reporter;
            this.owner = owner;
            this.workType = workType;
            this.webType = webType;
        }

        String fields() {
            return WORKITEM_FIELDS + title + "," + reporter + "," + owner;
        }
    }

    private record ItemRef(String workspace, Kind kind, String id) {
        ItemRef {
            if (!isId(workspace) || !isId(id)) throw invalid("Invalid TAPD work item identity");
        }

        String externalId() {
            return workspace + ":" + kind.webType + ":" + id;
        }

        static ItemRef parse(String externalId) {
            String[] parts = externalId == null ? new String[0] : externalId.split(":", -1);
            if (parts.length != 3) throw invalid("Invalid TAPD work item identity");
            Kind kind = switch (parts[1]) {
                case "story" -> Kind.STORY;
                case "bug" -> Kind.BUG;
                default -> throw invalid("Invalid TAPD work item type");
            };
            return new ItemRef(parts[0], kind, parts[2]);
        }
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public PageResult<ExternalWorkitemSummary> searchProject(ExternalPlatformConfig config, String workspace,
                                                             Date from, Date to) {
        requireWorkspace(config, workspace);
        Instant lower = from == null ? Instant.now().minus(Duration.ofDays(7)) : from.toInstant();
        Map<String, ExternalWorkitemSummary> items = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            for (int page = 1; ; page++) {
                JsonNode rows = itemPage(config, workspace, kind, page, PAGE_SIZE);
                boolean olderRows = false;
                for (JsonNode row : rows) {
                    ExternalWorkitemDetail item = detail(row.path(kind.wrapper), workspace, kind);
                    Date modified = item.getUpdatedAt();
                    if (modified == null) throw invalid("TAPD item has no valid modification time");
                    if (modified.toInstant().isBefore(lower)) {
                        olderRows = true;
                    } else if (to == null || !modified.after(to)) {
                        items.put(item.getExternalId(), item);
                    }
                }
                if (rows.size() < PAGE_SIZE || olderRows) break;
                if (page == MAX_PAGES) throw invalid("TAPD sync window exceeds the page limit");
            }
        }
        return pageOf(new ArrayList<>(items.values()));
    }

    @Override
    public PageResult<ExternalWorkitemSummary> searchProjectFirstPage(ExternalPlatformConfig config, String workspace) {
        requireWorkspace(config, workspace);
        List<ExternalWorkitemSummary> items = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (JsonNode row : itemPage(config, workspace, kind, 1, 1)) {
                items.add(detail(row.path(kind.wrapper), workspace, kind));
            }
        }
        return pageOf(items);
    }

    private JsonNode itemPage(ExternalPlatformConfig config, String workspace, Kind kind, int page, int limit) {
        return array(client.get(config, kind.endpoint, Map.of("workspace_id", workspace, "fields", kind.fields(),
                "order", "modified desc", "page", String.valueOf(page), "limit", String.valueOf(limit))));
    }

    @Override
    public PageResult<ExternalWorkitemSummary> searchByIds(ExternalPlatformConfig config, String workspace,
                                                           List<String> ids) {
        requireWorkspace(config, workspace);
        List<ExternalWorkitemSummary> items = new ArrayList<>();
        for (String id : ids.stream().distinct().toList()) {
            ItemRef ref = ItemRef.parse(id);
            if (!workspace.equals(ref.workspace)) throw invalid("TAPD work item belongs to another project");
            ExternalWorkitemDetail item = fetch(config, ref);
            if (item != null) items.add(item);
        }
        return pageOf(items);
    }

    @Override
    public ExternalWorkitemDetail getWorkitem(ExternalPlatformConfig config, String externalWorkitemId) {
        ItemRef ref = ItemRef.parse(externalWorkitemId);
        requireWorkspace(config, ref.workspace);
        return fetch(config, ref);
    }

    private ExternalWorkitemDetail fetch(ExternalPlatformConfig config, ItemRef ref) {
        JsonNode rows = array(client.get(config, ref.kind.endpoint,
                Map.of("workspace_id", ref.workspace, "id", ref.id, "fields", ref.kind.fields())));
        if (rows.isEmpty()) return null;
        ExternalWorkitemDetail item = detail(rows.get(0).path(ref.kind.wrapper), ref.workspace, ref.kind);
        if (!ref.externalId().equals(item.getExternalId())) throw invalid("TAPD returned an unexpected item");
        return item;
    }

    @Override
    public List<ExternalComment> listComments(ExternalPlatformConfig config, List<String> ids) {
        // The pilot imports work items. Comments are only read by the writeback recovery flow.
        return List.of();
    }

    @Override
    public ExternalComment createComment(ExternalPlatformConfig config, String id, String author, String contentMd) {
        if (author == null || author.isBlank()) throw invalid("TAPD comment author is required");
        ItemRef ref = ItemRef.parse(id);
        requireWorkspace(config, ref.workspace);
        String html = "<p>" + HtmlUtils.htmlEscape(contentMd.replaceAll("<!-- (an-op:[a-f0-9]{24}) -->", "$1"))
                .replace("\r\n", "\n").replace("\n", "<br>") + "</p>";
        JsonNode posted = client.addComment(config, Map.of("workspace_id", ref.workspace, "entry_id", ref.id,
                "entry_type", ref.kind == Kind.STORY ? "stories" : "bug",
                "description", html, "author", author)).path("Comment");
        String commentId = posted.path("id").asText();
        if (!isId(commentId)) {
            throw new ExternalPlatformException("TAPD comment response missing its ID", new IOException());
        }
        ExternalComment comment = new ExternalComment();
        comment.setExternalId(commentId);
        comment.setExternalWorkitemId(id);
        comment.setUpdatedAt(date(posted.path("modified").asText(null)));
        return comment;
    }

    boolean hasCommentMarker(ExternalPlatformConfig config, String id, String marker) {
        ItemRef ref = ItemRef.parse(id);
        requireWorkspace(config, ref.workspace);
        if (!marker.startsWith(COMMENT_MARKER)) throw invalid("Invalid TAPD writeback marker");
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode rows = array(client.get(config, "comments", Map.of("workspace_id", ref.workspace,
                    "entry_id", ref.id, "entry_type", ref.kind == Kind.STORY ? "stories" : "bug",
                    "page", String.valueOf(page), "limit", String.valueOf(PAGE_SIZE))));
            for (JsonNode row : rows) {
                if (row.path("Comment").path("description").asText().contains(marker)) return true;
            }
            if (rows.size() < PAGE_SIZE) break;
        }
        return false;
    }

    @Override
    public Map<String, List<ExternalStatusOption>> listOperationalStatuses(ExternalPlatformConfig config,
                                                                           String author, List<String> ids) {
        return Map.of();
    }

    @Override
    public void updateStatus(ExternalPlatformConfig config, String id, String author, String status) {
        throw invalid("TAPD status writeback is not configured");
    }

    @Override
    public void updateContent(ExternalPlatformConfig config, String id, String author, String title, String content) {
        throw invalid("TAPD owns work item content");
    }

    private ExternalWorkitemDetail detail(JsonNode row, String workspace, Kind kind) {
        ItemRef ref = new ItemRef(workspace, kind, row.path("id").asText());
        if (!workspace.equals(row.path("workspace_id").asText()) || row.path(kind.title).asText().isBlank()) {
            throw invalid("TAPD returned an item outside the project or without a title");
        }
        ExternalWorkitemDetail item = new ExternalWorkitemDetail();
        item.setExternalProjectId(workspace);
        item.setExternalId(ref.externalId());
        item.setExternalIssueTypeId(kind.webType);
        item.setWorkType(kind.workType);
        item.setTitle(row.path(kind.title).asText());
        item.setContentMd(plainDescription(row.path("description").asText("")));
        item.setStatusId(row.path("status").asText(null));
        item.setStatusName(item.getStatusId());
        item.setCreatedAt(date(row.path("created").asText(null)));
        Date modified = date(row.path("modified").asText(null));
        item.setUpdatedAt(modified == null ? item.getCreatedAt() : modified);
        item.setPriority(priority(row.path("priority").asText()));
        item.setReporter(principal(row, kind.reporter));
        item.setBusinessOwner(principal(row, kind.owner));
        item.setExternalUrl("https://www.tapd.cn/tapd_fe/" + workspace + "/" + kind.webType + "/detail/" + ref.id);
        item.setRawJson(row.toString());
        return item;
    }

    private static ExternalPrincipalRef principal(JsonNode row, String field) {
        String value = row.path(field).asText(null);
        return ExternalPrincipalRef.user(value, value);
    }

    private static String plainDescription(String html) {
        String text = html.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", "")
                .replaceAll("(?i)<br\\s*/?>|</(?:p|div|li|tr)\\s*>", "\n")
                .replaceAll("<[^>]+>", "");
        return HtmlUtils.htmlUnescape(text).replace('\u00a0', ' ').strip();
    }

    private static Date date(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Date.from(LocalDateTime.parse(text, TAPD_DATE).atZone(TAPD_ZONE).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer priority(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "high", "urgent" -> 1;
            case "medium", "middle", "normal" -> 2;
            case "low" -> 3;
            default -> null;
        };
    }

    private static PageResult<ExternalWorkitemSummary> pageOf(List<ExternalWorkitemSummary> items) {
        return PageResult.of(items, 1, items.size(), items.size());
    }

    private static JsonNode array(JsonNode data) {
        if (!data.isArray()) throw invalid("TAPD did not return a list");
        return data;
    }

    public static boolean isId(String value) {
        return value != null && value.matches("[1-9][0-9]{0,19}");
    }

    private static void requireWorkspace(ExternalPlatformConfig config, String value) {
        if (!isId(value) || !value.equals(config.externalProjectId())) {
            throw invalid("TAPD project does not match the binding");
        }
    }

    private static ExternalPlatformException invalid(String message) {
        return new ExternalPlatformException(message, true);
    }
}
