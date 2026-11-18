package com.ainative.v2.workitem;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.common.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 工作项域接口:契约与 v1 /api/workitems/* 对齐。 */
@Tag(name = "需求交付", description = "工单 CRUD、状态流转与负责人指派")
@RestController
@RequestMapping("/api/workitems")
public class WorkitemController {
    public record CreateRequest(String workType, String title, String contentMd, Integer priority) {}
    public record ContentRequest(String title, String contentMd, Integer expectedVersion) {}
    public record TransitionRequest(Long toNodeId, Long fromNodeId, Integer expectedVersion) {}
    public record AssignRequest(String assigneeType, Long assigneeRef, Long squadId, Long sdlcId) {}
    public record TagsRequest(List<String> tags) {}
    public record CommentRequest(String contentMd) {}

    private final WorkitemService service;

    public WorkitemController(WorkitemService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "创建工单。")
    public ApiResult<WorkitemService.WorkitemVO> create(@RequestBody CreateRequest req) {
        return ApiResult.ok(service.create(req.workType(), req.title(), req.contentMd(), req.priority(),
                RequestContext.requireUserId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "列出工单。")
    public ApiResult<WorkitemService.WorkitemVO> get(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.get(id));
    }

    @GetMapping
    @Operation(summary = "列出工单。")
    public ApiResult<PageResult<WorkitemService.WorkitemVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String workType,
            @RequestParam(required = false) Long statusNodeId,
            @RequestParam(required = false) String assigneeType,
            @RequestParam(required = false) Long assigneeRef,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String statusCategory,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.list(new WorkitemDao.ListQuery(keyword, workType, statusNodeId,
                assigneeType, assigneeRef, priority, tag, false, pageNum, pageSize, statusCategory)));
    }

    @GetMapping("/tag-options")
    @Operation(summary = "列出工单。")
    public ApiResult<List<String>> tagOptions() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.tagOptions());
    }

    @PutMapping("/{id}/content")
    @Operation(summary = "更新工单。")
    public ApiResult<WorkitemService.WorkitemVO> updateContent(@PathVariable long id, @RequestBody ContentRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.updateContent(id, req.title(), req.contentMd(), req.expectedVersion()));
    }

    @PostMapping("/{id}/transition")
    @Operation(summary = "工单·流转状态。")
    public ApiResult<WorkitemService.WorkitemVO> transition(@PathVariable long id, @RequestBody TransitionRequest req) {
        RequestContext.requireUserId();
        if (req.toNodeId() == null) {
            throw new com.ainative.v2.common.BizException("13004", "非法状态流转");
        }
        Integer from = req.fromNodeId() == null ? null : req.fromNodeId().intValue();
        return ApiResult.ok(service.transition(id, req.toNodeId(), from, req.expectedVersion()));
    }

    @PutMapping("/{id}/assignee")
    @Operation(summary = "更新工单。")
    public ApiResult<WorkitemService.WorkitemVO> assign(@PathVariable long id, @RequestBody AssignRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.assign(id, req.assigneeType(), req.assigneeRef(), req.squadId(), req.sdlcId()));
    }

    @PutMapping("/{id}/tags")
    @Operation(summary = "更新工单。")
    public ApiResult<WorkitemService.WorkitemVO> tags(@PathVariable long id, @RequestBody TagsRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.replaceTags(id, req.tags()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除工单。")
    public ApiResult<Void> delete(@PathVariable long id) {
        RequestContext.requireUserId();
        service.delete(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/recycled")
    @Operation(summary = "列出工单。")
    public ApiResult<PageResult<WorkitemService.WorkitemVO>> recycled(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.recycled(pageNum, pageSize));
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "工单·恢复。")
    public ApiResult<WorkitemService.WorkitemVO> restore(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.restore(id));
    }

    @PostMapping("/{id}/comments")
    @Operation(summary = "工单·评论。")
    public ApiResult<Map<String, Object>> comment(@PathVariable long id, @RequestBody CommentRequest req) {
        return ApiResult.ok(service.comment(id, RequestContext.requireUserId(), req.contentMd()));
    }

    @GetMapping("/{id}/comments")
    @Operation(summary = "工单·评论。")
    public ApiResult<List<Map<String, Object>>> comments(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.comments(id));
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "列出工单。")
    public ApiResult<List<Map<String, Object>>> timeline(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.timeline(id));
    }

    @GetMapping("/{id}/unified-timeline")
    @Operation(summary = "列出工单。")
    public ApiResult<List<Map<String, Object>>> unifiedTimeline(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.unifiedTimeline(id));
    }

    @GetMapping("/{id}/participants")
    @Operation(summary = "工单·参与方。")
    public ApiResult<List<Map<String, Object>>> participants(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.participants(id));
    }

    @GetMapping("/{id}/watchers")
    @Operation(summary = "列出工单。")
    public ApiResult<List<Long>> watchers(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.watchers(id));
    }

    @PostMapping("/{id}/watch")
    @Operation(summary = "创建工单。")
    public ApiResult<Void> watch(@PathVariable long id) {
        service.watch(id, RequestContext.requireUserId());
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}/watch")
    @Operation(summary = "删除工单。")
    public ApiResult<Void> unwatch(@PathVariable long id) {
        service.unwatch(id, RequestContext.requireUserId());
        return ApiResult.ok(null);
    }
}
