package com.ainative.v2.cv;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 工作项澄清结论接口(与 v1 /api/workitems/{id}/clarification 对齐)。 */
@Tag(name = "需求交付", description = "需求澄清会话与澄清结论")
@RestController
@RequestMapping("/api/workitems/{workitemId}/clarification")
public class ClarificationController {
    public record SaveRequest(String contentMd) {}

    private final CvService service;

    public ClarificationController(CvService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "工作项澄清结论接口(与 v1 /api/workitems/{id}/clarification 对齐)。")
    public ApiResult<Map<String, Object>> get(@PathVariable long workitemId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.clarification(workitemId));
    }

    @PutMapping
    @Operation(summary = "更新需求澄清。")
    public ApiResult<Map<String, Object>> save(@PathVariable long workitemId, @RequestBody SaveRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.saveClarification(workitemId, req.contentMd()));
    }
}
