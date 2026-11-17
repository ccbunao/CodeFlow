package com.ainative.v2.agent;

import com.ainative.v2.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 智能体域服务:Agent CRUD/配置、执行器、MCP 令牌。 */
@Service
public class AgentService {
    private final AgentDao dao;
    private final com.ainative.v2.daemon.ExecutorHub executorHub;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Value("${ainative.public-base-url:http://localhost:7011}")
    private String publicBaseUrl;

    /**
     * 执行器安装源:决定「启动命令」里怎么装运行时。
     * - release(默认):pip 装 GitHub Release 的 wheel —— 要求仓库已公开,否则匿名下载 404
     * - npm:            npx -y ymai-codeflow@&lt;version&gt; —— 要求 npm 上有该版本
     * - local:          假定运行时已在 PATH(自行 pip/uv 装过),命令里只给 connect
     * 私有仓库请用 local 或 npm,避免生成一条 404 的安装命令。
     */
    @org.springframework.beans.factory.annotation.Value("${ainative.runtime-source:release}")
    private String runtimeSource;

    /** 自定义安装前缀(留空则按 runtime-source 自动生成)。 */
    @org.springframework.beans.factory.annotation.Value("${ainative.runtime-install-prefix:}")
    private String runtimeInstallPrefix;

    public AgentService(AgentDao dao, com.ainative.v2.daemon.ExecutorHub executorHub) {
        this.dao = dao;
        this.executorHub = executorHub;
    }

    public Map<String, Object> createAgent(String name, String roleCode, String roleName, String soulMd) {
        if (name == null || name.isBlank()) {
            throw new BizException("10001", "名称不能为空");
        }
        return agentDetail(dao.insertAgent(name.trim(), roleCode, roleName, soulMd));
    }

    public Map<String, Object> agentDetail(long id) {
        AgentDao.Agent a = dao.findAgent(id).orElseThrow(() -> new BizException("17001", "智能体不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("name", a.name());
        m.put("roleCode", a.roleCode());
        m.put("roleName", a.roleName());
        m.put("soulMd", a.soulMd());
        m.put("status", a.status());
        m.put("executors", executorsOf(id));
        return m;
    }

    public List<Map<String, Object>> listAgents() {
        return dao.listAgents().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("name", a.name());
            m.put("roleCode", a.roleCode());
            m.put("roleName", a.roleName());
            m.put("status", a.status());
            m.put("executorCount", dao.executorsOf(a.id()).size());
            m.put("onlineExecutorCount", dao.executorsOf(a.id()).stream()
                    .filter(e -> "ONLINE".equals(e.status())).count());
            return m;
        }).toList();
    }

    @Transactional
    public Map<String, Object> updateConfig(long id, String roleName, String soulMd) {
        dao.findAgent(id).orElseThrow(() -> new BizException("17001", "智能体不存在"));
        dao.updateAgentConfig(id, roleName, soulMd);
        return agentDetail(id);
    }

    @Transactional
    public void deleteAgent(long id) {
        dao.findAgent(id).orElseThrow(() -> new BizException("17001", "智能体不存在"));
        dao.deleteAgent(id);
    }

    // ── 执行器 ──
    public List<Map<String, Object>> executorsOf(long agentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentDao.Executor e : dao.executorsOf(agentId)) {
            out.add(executorVO(e));
        }
        return out;
    }

    public List<Map<String, Object>> listExecutors() {
        return dao.listExecutors().stream().map(this::executorVO).toList();
    }

    public Map<String, Object> executor(long id) {
        AgentDao.Executor e = dao.findExecutor(id).orElseThrow(() -> new BizException("17002", "执行器不存在"));
        return executorVO(e);
    }

    @Transactional
    public Map<String, Object> createExecutor(long agentId, String name, String clientKind) {
        dao.findAgent(agentId).orElseThrow(() -> new BizException("17001", "智能体不存在"));
        String token = "exec_" + agentId + "_" + randomToken(24);
        long id = dao.insertExecutor(agentId, name == null || name.isBlank() ? "executor" : name.trim(),
                clientKind == null || clientKind.isBlank() ? "CLAUDE_CODE" : clientKind, sha256(token));
        Map<String, Object> m = new LinkedHashMap<>(executor(id));
        // 明文令牌只在创建时返回一次(v1 同语义)
        m.put("token", token);
        return m;
    }

    @Transactional
    public void deleteExecutor(long id) {
        dao.findExecutor(id).orElseThrow(() -> new BizException("17002", "执行器不存在"));
        dao.deleteExecutor(id);
    }

    @Transactional
    public Map<String, Object> heartbeat(long id, String status) {
        dao.findExecutor(id).orElseThrow(() -> new BizException("17002", "执行器不存在"));
        dao.updateExecutorStatus(id, status == null ? "ONLINE" : status);
        return executor(id);
    }

    /** 轮换执行器令牌:旧令牌立即失效,返回新明文(仅此一次)。 */
    @Transactional
    public Map<String, Object> rotateToken(long executorId) {
        AgentDao.Executor e = dao.findExecutor(executorId).orElseThrow(() -> new BizException("17002", "执行器不存在"));
        String token = "exec_" + e.agentId() + "_" + randomToken(24);
        dao.updateExecutorToken(executorId, sha256(token));
        executorHub.close(executorId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", executorId);
        m.put("token", token);
        return m;
    }

    /**
     * 运行器启动命令(与 ymai-codeflow CLI 参数契约一致)。
     * 未提供 token 时自动轮换并随响应返回(token 字段)。
     */
    @Transactional
    public Map<String, Object> launchCommand(long executorId, String token, String os, String mode) {
        AgentDao.Executor e = dao.findExecutor(executorId).orElseThrow(() -> new BizException("17002", "执行器不存在"));
        String effective = token;
        if (effective == null || effective.isBlank()) {
            effective = (String) rotateToken(executorId).get("token");
        }
        String base = publicBaseUrl.replaceFirst("^http", "ws");
        String provider = "claude";
        int maxTasks = 2;
        // 运行时版本锁定:平台 v2 的协议依赖 0.3.33 的行为(产物递归回传/环境变量合并/
        // 会话去重复合键)。不锁版本会让用户拉到旧版而命中必现故障。
        String runtimeVersion = runtimeVersion();
        String connectArgs = " connect"
                + " --ws-url " + base + "/ws/executor"
                + " --token " + effective
                + " --executor-id " + executorId
                + " --provider " + provider
                + " --memory-mode provider-local"
                + " --max-tasks " + maxTasks;
        String source = runtimeSource == null ? "release" : runtimeSource.trim().toLowerCase();
        String npmCommand = "npx -y ymai-codeflow@" + runtimeVersion + connectArgs;
        String command;
        if ("local".equals(source)) {
            // 运行时已就位:命令里只给 connect,安装交给用户自己的环境
            command = "ainative" + connectArgs;
        } else if ("npm".equals(source)) {
            command = npmCommand;
        } else {
            // release(默认):独立 venv 安装官方 wheel(零依赖、跨平台、与仓库源码同一份)。
            // 不直接 pip install 到系统 Python:macOS/Homebrew 的 externally-managed 环境会拒绝。
            // 注意:仓库为 private 时匿名下载会 404 —— 此时请用 runtime-source=local 或 npm。
            String wheel = runtimeInstallPrefix != null && !runtimeInstallPrefix.isBlank()
                    ? runtimeInstallPrefix
                    : "https://github.com/qsumm/CodeFlow/releases/download/runtime-v" + runtimeVersion
                      + "/ainative_runtime-" + runtimeVersion + "-py3-none-any.whl";
            command = "posix".equalsIgnoreCase(os)
                    ? "python3 -m venv ~/.ainative-runtime && ~/.ainative-runtime/bin/pip install " + wheel
                      + " && ~/.ainative-runtime/bin/ainative" + connectArgs
                    : "python -m venv %USERPROFILE%\\.ainative-runtime"
                      + " && %USERPROFILE%\\.ainative-runtime\\Scripts\\pip install " + wheel
                      + " && %USERPROFILE%\\.ainative-runtime\\Scripts\\ainative" + connectArgs;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executorId", executorId);
        out.put("clientKind", e.clientKind());
        out.put("provider", provider);
        out.put("command", command);
        out.put("npmCommand", npmCommand);
        out.put("runtimeVersion", runtimeVersion);
        out.put("runtimeSource", source);
        if (token == null || token.isBlank()) {
            out.put("token", effective);
        }
        return out;
    }

    /** 运行时版本:与 GitHub Release tag / npm 包版本一致。升级运行时时只改这里。 */
    private static final String RUNTIME_VERSION = "0.3.33";

    private String runtimeVersion() {
        return RUNTIME_VERSION;
    }

    /** 记录「编辑完整命令」的手工覆盖(变更流水,审计可查;运行器心跳拉取 update 时透传)。 */
    @Transactional
    public void recordLaunchCommandOverride(long executorId, Integer version, String detailJson) {
        dao.findExecutor(executorId).orElseThrow(() -> new BizException("17002", "执行器不存在"));
        dao.insertExecutorUpdate(executorId, detailJson);
    }

    public Map<String, Object> modelPolicy(long executorId) {
        AgentDao.Executor e = dao.findExecutor(executorId).orElseThrow(() -> new BizException("17002", "执行器不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policy", dao.modelPolicy(executorId));
        return m;
    }

    @Transactional
    public Map<String, Object> setModelPolicy(long executorId, String policy) {
        dao.findExecutor(executorId).orElseThrow(() -> new BizException("17002", "执行器不存在"));
        String p = policy == null ? "FOLLOW" : policy.toUpperCase();
        if (!p.equals("FOLLOW") && !p.equals("PIN") && !p.equals("MANUAL")) {
            throw new BizException("10001", "不支持的模型策略");
        }
        dao.updateModelPolicy(executorId, p);
        return modelPolicy(executorId);
    }

    // ── MCP 令牌 ──
    public List<Map<String, Object>> listMcpTokens(Long userId) {
        return dao.listMcpTokens(userId).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("name", t.name());
            m.put("expiresAt", t.expiresAt());
            return m;
        }).toList();
    }

    @Transactional
    public Map<String, Object> createMcpToken(Long userId, String name) {
        String token = "mcp_" + randomToken(32);
        long id = dao.insertMcpToken(userId, name == null || name.isBlank() ? "MCP 令牌" : name.trim(),
                sha256(token), null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("token", token);
        return m;
    }

    @Transactional
    public void revokeMcpToken(long id) {
        dao.revokeMcpToken(id);
    }

    private Map<String, Object> executorVO(AgentDao.Executor e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("agentId", e.agentId());
        m.put("name", e.name());
        m.put("clientKind", e.clientKind());
        m.put("status", e.status());
        m.put("lastHeartbeat", e.lastHeartbeat());
        return m;
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
