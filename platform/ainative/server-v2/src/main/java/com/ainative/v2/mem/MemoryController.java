package com.ainative.v2.mem;

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

import java.util.Map;

/** 记忆接口(/api/memories)。 */
@Tag(name = "配置资产", description = "AI 记忆的读写与统计")
@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    public record MemoryRequest(String title, String contentMd) {}

    private final MemoryService service;

    public MemoryController(MemoryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "记忆接口(/api/memories)。")
    public ApiResult<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.list(keyword, pageNum, pageSize));
    }

    @GetMapping("/count")
    @Operation(summary = "AI 记忆·计数。")
    public ApiResult<Long> count() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.list(null, 1, 1).total());
    }

    @GetMapping("/grouped/count")
    @Operation(summary = "AI 记忆·计数。")
    public ApiResult<Map<String, Object>> groupedCount() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.groupedCount());
    }

    @GetMapping("/{id}")
    @Operation(summary = "列出AI 记忆。")
    public ApiResult<Map<String, Object>> get(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.get(id));
    }

    @PostMapping
    @Operation(summary = "创建AI 记忆。")
    public ApiResult<Map<String, Object>> create(@RequestBody MemoryRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.create(req.title(), req.contentMd()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新AI 记忆。")
    public ApiResult<Map<String, Object>> update(@PathVariable long id, @RequestBody MemoryRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.update(id, req.title(), req.contentMd()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除AI 记忆。")
    public ApiResult<Void> delete(@PathVariable long id) {
        RequestContext.requireUserId();
        service.delete(id);
        return ApiResult.ok(null);
    }
}
