package com.ainative.v2.common;

/**
 * 请求级上下文:当前用户与工作区(由 AuthFilter 注入)。
 *
 * <p><b>工作区的两种来源,不可混用</b>:
 * <ul>
 *   <li><b>请求内</b>:令牌带了工作区 → 用令牌里的;匿名请求(登录页、健康检查)
 *       没带 → 回退到 1,兼容单工作区部署。</li>
 *   <li><b>请求外</b>(工作节点 WS 线程、定时调度线程、异步线程)→ <b>没有工作区可用</b>。
 *       此前这里静默回退到 1,于是非默认项目的数据(澄清回合、用量、事件、工单…)
 *       全被挂到 1 号项目名下,而调用方毫无察觉。现改为直接抛错:
 *       <b>宁可失败,也不写错地方</b>。后台任务必须显式传入工作区
 *       (见 {@link #requireWorkspaceId()})。</li>
 * </ul>
 */
public final class RequestContext {
    private static final ThreadLocal<Long> UID = new ThreadLocal<>();
    private static final ThreadLocal<Long> WORKSPACE = new ThreadLocal<>();
    /** 是否处于请求处理中(请求内允许回退 1;请求外一律要求显式工作区)。 */
    private static final ThreadLocal<Boolean> IN_REQUEST = new ThreadLocal<>();

    private RequestContext() {}

    public static void setUserId(Long uid) {
        UID.set(uid);
    }

    public static Long userId() {
        return UID.get();
    }

    public static void setWorkspaceId(Long workspaceId) {
        WORKSPACE.set(workspaceId);
    }

    /** 标记请求边界:请求处理开始时调用。 */
    public static void markInRequest() {
        IN_REQUEST.set(Boolean.TRUE);
    }

    /** 是否处于请求处理中。 */
    public static boolean inRequest() {
        return Boolean.TRUE.equals(IN_REQUEST.get());
    }

    /**
     * 当前工作区。
     *
     * @throws IllegalStateException 请求外(后台线程)且未显式指定工作区时抛出。
     *         这是刻意的:静默回退到 1 会把别的项目的数据挂到 1 号项目名下,
     *         而现场毫无线索(2026-11-23 排查 15 处硬编码时发现)。
     */
    public static long workspaceId() {
        Long ws = WORKSPACE.get();
        if (ws != null) {
            return ws;
        }
        if (inRequest()) {
            // 匿名请求(登录/健康检查等):单工作区部署兼容
            return 1L;
        }
        throw new IllegalStateException(
                "工作区缺失:后台线程必须显式指定工作区(不要依赖请求上下文回退值)");
    }

    /** 后台任务专用:显式工作区,缺失即报错。语义与 {@link #workspaceId()} 一致。 */
    public static long requireWorkspaceId() {
        return workspaceId();
    }

    public static long requireUserId() {
        Long uid = UID.get();
        if (uid == null) {
            throw new BizException("10401", "未登录或登录已失效");
        }
        return uid;
    }

    public static void clear() {
        UID.remove();
        WORKSPACE.remove();
        IN_REQUEST.remove();
    }
}
