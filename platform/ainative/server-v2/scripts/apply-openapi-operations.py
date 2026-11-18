#!/usr/bin/env python3
"""为公开端点方法补 @Operation 说明。

只处理**真正的端点方法**:紧跟在 @Get/@Post/@Put/@Delete/@PatchMapping 之下的
public 方法;record 声明、内部辅助方法一律不动。

说明来源优先级:
1) 方法上方的 javadoc 首句(与代码注释一致,最准确);
2) 无 javadoc 时,按「HTTP 动词 + 路径资源 + 控制器领域」生成中性描述,
   只描述行为不编造语义(如 「列出交付流程模板」「创建工单」)。
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src/main/java"

RESOURCE = {
    "AuthController": "账号", "AuthTraceController": "登录链路", "UserSelfServiceController": "个人资料",
    "PlatformAdminController": "平台用户", "PlatformController": "平台配置",
    "WorkitemController": "工单", "ClarificationController": "需求澄清",
    "ClarificationConversationController": "澄清会话", "ConversationController": "数字员工会话",
    "DispatchController": "派发执行", "UsageController": "AI 用量",
    "ExecutorController": "运行节点", "ExecutorOpsController": "运行节点",
    "AgentController": "AI 数字员工", "AgentResourceController": "数字员工资源",
    "SquadController": "编队", "SquadCopyController": "编队",
    "SdlcController": "交付流程模板", "ScheduledTaskOpsController": "定时任务",
    "ScheduledTaskRunController": "定时任务运行",
    "AssetController": "代码仓库", "MemoryController": "AI 记忆", "SkillController": "AI 技能",
    "StatusTemplateController": "状态模板", "StatusTemplateEditController": "状态模板",
    "DashboardController": "总览看板", "AuditController": "操作审计",
    "ExternalIntegrationController": "外部系统绑定", "ZentaoController": "禅道绑定",
    "TapdIntegrationController": "TAPD 绑定", "NotifyPrefController": "通知偏好",
    "McpTokenController": "MCP 令牌", "McpServerController": "MCP 服务",
    "ChiefOfStaffController": "AI 管家", "GapsController": "运行器运维",
    "MiscController": "AI 会话", "RootForwardController": "页面路由",
    "WorkspaceController": "项目", "DaemonArtifactController": "交付产物",
    "DaemonCommentController": "交付结论", "DaemonPackageController": "任务包",
}

ACTION = {
    "heartbeat": "上报心跳", "restart": "重启运行节点", "rotate": "轮换令牌", "run-now": "立即触发",
    "pull": "拉取单据", "copy": "复制", "apply": "应用", "transition": "流转状态",
    "assign": "指派负责人", "comments": "评论", "members": "成员", "steps": "步骤",
    "category": "分类", "conclusion": "交付结论", "relations": "依赖关系",
    "status-mappings": "状态映射", "bindings": "绑定", "tools": "工具清单",
    "identities": "IM 身份", "profile": "个人资料", "password": "密码",
    "settings": "设置", "prefs": "通知偏好", "audit-logs": "操作审计",
    "notifications": "通知", "unread-count": "未读数", "branding": "品牌",
    "config": "配置", "recovery": "恢复控制", "delivery-progress": "交付进度",
    "runtime-trace": "运行轨迹", "events": "事件", "launch-config": "启动参数",
    "launch-command": "启动命令", "token": "令牌", "update": "更新",
    "import": "导入", "upload": "上传", "preview": "预览", "approve": "审批",
    "reject": "驳回", "validate": "校验", "release": "发布", "restore": "恢复",
    "clone": "克隆", "switch": "切换", "reorder": "重排", "batch": "批量",
    "route": "路由", "control": "控制", "detail": "详情", "list": "列表",
    "page": "分页列表", "info": "信息", "status": "状态", "health": "健康度",
    "artifacts": "产物", "participants": "参与方", "usage": "用量", "runs": "运行记录",
    "derived-workitems": "派生工单", "open-platform": "开放平台", "count": "计数",
    "grouped": "分组统计", "options": "选项", "exec": "执行", "repair": "修复",
}

MAPPING_RE = re.compile(
    r"@(?:org\.springframework\.web\.bind\.annotation\.)?(Get|Post|Put|Delete|Patch)Mapping"
    r"(?:\((?:[^()]|\([^()]*\))*\))?")
CLASS_BASE_RE = re.compile(r"@RequestMapping\(\s*\"([^\"]*)\"\s*\)")


def javadoc_first_sentence(text: str, upto: int, floor: int = 0) -> str:
    """取 [floor, upto) 区间内最近的一段 javadoc 首句(限定区间避免拿到类注释)。"""
    seg = text[floor:upto]
    pos = seg.rfind("/**")
    if pos < 0:
        return ""
    end = seg.find("*/", pos)
    if end < 0:
        return ""
    lines = [re.sub(r"^\s*\*\s?", "", l).strip() for l in seg[pos + 3:end].splitlines()]
    lines = [l for l in lines if l and not l.startswith("@")]
    if not lines:
        return ""
    s = lines[0]
    return s if s.endswith(("。", ".", "!", "！")) else s + "。"


def describe(http: str, full_path: str, method_path: str, resource: str, name: str) -> str:
    segs = [s for s in full_path.split("/") if s and not s.startswith("{")]
    last = segs[-1] if segs else ""
    if last in ACTION:
        act = ACTION[last]
        if act.startswith(("列出", "查看")):
            return f"{act}{resource}。"
        return f"{resource}·{act}。"
    verb = {"GET": "列出", "POST": "创建", "PUT": "更新", "DELETE": "删除", "PATCH": "更新"}.get(http, "处理")
    if method_path and not method_path.startswith("/"):
        return f"{verb}{resource}·{ACTION.get(method_path, method_path)}。"
    return f"{verb}{resource}。"


def esc(text: str) -> str:
    """Java 字符串字面量转义:summary 里可能含引号(如 裸 "/" 时)。"""
    return text.replace("\\", "\\\\").replace('"', '\\"')


def method_name_after(text: str, pos: int) -> str:
    m = re.search(r"public\s+[\w<>,\[\]\.\?&\s]+?\s+(\w+)\s*\(", text[pos:pos + 400])
    return m.group(1) if m else ""


def main() -> int:
    changed_files = 0
    total_ops = 0
    for f in sorted(ROOT.rglob("*Controller.java")):
        text = f.read_text(encoding="utf-8")
        if "@Operation(" in text:
            continue
        resource = RESOURCE.get(f.stem, f.stem.replace("Controller", ""))
        base_m = CLASS_BASE_RE.search(text)
        base = base_m.group(1) if base_m else ""
        pieces = []
        last = 0
        for m in MAPPING_RE.finditer(text):
            map_end = m.end()
            name = method_name_after(text, map_end)
            if not name:
                continue
            between = text[map_end:map_end + 800]
            if between.lstrip().startswith("@Operation("):
                continue
            http = m.group(1).upper()
            lit = re.search(r'"([^"]*)"', m.group(0) or "")
            method_path = lit.group(1) if lit else ""
            doc = javadoc_first_sentence(text, map_end, max(0, m.start() - 500))
            if not doc:
                doc = javadoc_first_sentence(text, map_end, max(0, m.start() - 500))
            if not doc:
                doc = describe(http, base + method_path, method_path, resource, name)
            if doc in ("。", ""):
                continue
            line_start = text.rfind("\n", 0, map_end) + 1
            indent = text[line_start:m.start()]
            pieces.append(text[last:map_end])
            pieces.append(f"\n{indent}@Operation(summary = \"{esc(doc)}\")")
            last = map_end
            total_ops += 1
        if not pieces:
            continue
        pieces.append(text[last:])
        new_text = "".join(pieces)
        if "import io.swagger.v3.oas.annotations.Operation;" not in new_text:
            new_text = new_text.replace("import io.swagger.v3.oas.annotations.tags.Tag;",
                                        "import io.swagger.v3.oas.annotations.Operation;\nimport io.swagger.v3.oas.annotations.tags.Tag;", 1)
        f.write_text(new_text, encoding="utf-8")
        changed_files += 1
    print(f"✓ {changed_files} 个控制器补齐 @Operation(共 {total_ops} 个端点)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
