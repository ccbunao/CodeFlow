package com.ainative.v2.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> biz(BizException ex) {
        return ApiResult.fail(ex.code(), ex.getMessage());
    }

    /**
     * 未知路径/未映射端点 → 404,而不是落到通用 500。
     * 前端据此区分「后端缺接口」与「后端出错」,排查时不会误判。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResult<Void> notFound(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return ApiResult.fail("10404", "接口不存在: " + ex.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> unexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return ApiResult.fail("10500", "服务器内部错误");
    }
}
