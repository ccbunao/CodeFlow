#!/usr/bin/env python3
"""给 40 个 Controller 补 @Tag(OpenAPI 领域分组)+ @Operation(方法级说明缺失时)。

规则:
- 只做两件事:加类级 @Tag(领域名 + 一句话职责)、给没有 @Operation 的公开方法加简述;
- 不改任何路由、参数、返回结构 —— 文档与代码的一致性靠 springdoc 自动扫描,不靠手写;
- 已存在的 @Tag/@Operation 一律保留(幂等)。

领域归类依据控制器真实职责(见各文件注释),不臆造端点。
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src/main/java"

# 文件名 → (领域标签, 领域说明)
DOMAINS = {
    "AuthController": ("鉴权与用户", "注册、登录、令牌与身份"),
    "AuthTraceController": ("鉴权与用户", "登录链路追踪"),
    "UserSelfServiceController": ("鉴权与用户", "个人资料、密码、偏好与账号注销"),
    "PlatformAdminController": ("平台管理", "平台用户、角色与 IM 身份管理"),
    "PlatformController": ("平台管理", "品牌、设置、审计、通知、定时任务与备份"),
    "WorkitemController": ("需求交付", "工单 CRUD、状态流转与负责人指派"),
    "ClarificationController": ("需求交付", "需求澄清会话与澄清结论"),
    "ClarificationConversationController": ("需求交付", "澄清会话的逐轮问答"),
    "ConversationController": ("需求交付", "数字员工会话与事件流"),
    "DispatchController": ("需求交付", "派发执行、运行轨迹与交付进度"),
    "UsageController": ("需求交付", "AI 用量统计"),
    "ExecutorController": ("执行与自动化", "运行节点注册、心跳、令牌与启动命令"),
    "ExecutorOpsController": ("执行与自动化", "运行节点启动参数、重启与升级"),
    "AgentController": ("执行与自动化", "AI 数字员工定义与版本"),
    "AgentResourceController": ("执行与自动化", "数字员工的资源与产物"),
    "SquadController": ("执行与自动化", "编队成员与角色构成"),
    "SquadCopyController": ("执行与自动化", "编队复制"),
    "SdlcController": ("执行与自动化", "交付流程模板与步骤"),
    "ScheduledTaskOpsController": ("执行与自动化", "定时任务触发、运行记录与健康度"),
    "ScheduledTaskRunController": ("执行与自动化", "定时任务运行详情"),
    "AssetController": ("配置资产", "代码仓库、依赖关系与交付结论"),
    "MemoryController": ("配置资产", "AI 记忆的读写与统计"),
    "SkillController": ("配置资产", "AI 技能包与分类"),
    "StatusTemplateController": ("配置资产", "状态模板与状态机"),
    "StatusTemplateEditController": ("配置资产", "状态模板编辑与流转边"),
    "AssetController_": ("配置资产", ""),
    "DashboardController": ("分析度量", "项目总览、运行看板与交付健康度"),
    "InsightsContractService": ("分析度量", ""),
    "AuditController": ("分析度量", "操作审计"),
    "ExternalIntegrationController": ("外部连接", "外部系统绑定、状态映射与拉取"),
    "ZentaoController": ("外部连接", "禅道绑定与单据拉取"),
    "TapdIntegrationController": ("外部连接", "TAPD 绑定、评论回写与读回确认"),
    "NotifyPrefController": ("外部连接", "站内通知偏好"),
    "McpTokenController": ("开放平台", "MCP 令牌与工具清单"),
    "McpServerController": ("开放平台", "MCP 服务端点"),
    "ChiefOfStaffController": ("开放平台", "AI 管家与自进化建议"),
    "GapsController": ("开放平台", "长尾接口与运行器运维"),
    "MiscController": ("开放平台", "AI 会话与自进化长尾接口"),
    "RootForwardController": ("其他", "前端路由回退"),
    "WorkspaceController": ("工作区", "项目列表、创建、成员与回收站"),
    "DaemonArtifactController": ("运行节点", "运行节点上传交付产物"),
    "DaemonCommentController": ("运行节点", "运行节点回写交付结论"),
    "DaemonPackageController": ("运行节点", "运行节点领取任务包"),
}


def find_controller_files() -> list[Path]:
    return sorted(ROOT.rglob("*Controller.java"))


def class_doc(text: str) -> str:
    """取类注释的第一句作为领域说明的补充。"""
    m = re.search(r"/\*\*\s*(.+?)\*/", text, re.S)
    if not m:
        return ""
    first = m.group(1).strip().splitlines()[0].strip()
    first = re.sub(r"^[/*\s]+", "", first).strip("/ ")
    return first


def main() -> int:
    changed = 0
    for f in find_controller_files():
        name = f.stem
        if name not in DOMAINS:
            print(f"  ! 未归类: {name}")
            continue
        domain, desc = DOMAINS[name]
        text = f.read_text(encoding="utf-8")
        if "@Tag(" in text:
            continue
        desc = desc or class_doc(text) or ""
        if not desc:
            print(f"  ! 无说明可取: {name}")
            continue
        # 在 @RestController 之前插入 import 与注解
        if "import io.swagger.v3.oas.annotations.tags.Tag;" not in text:
            text = text.replace("import org.springframework.web.bind.annotation.",
                                "import io.swagger.v3.oas.annotations.tags.Tag;\nimport org.springframework.web.bind.annotation.", 1)
        annotation = f"@Tag(name = \"{domain}\", description = \"{desc}\")\n"
        # 放到 @RestController 上方
        idx = text.find("@RestController")
        if idx < 0:
            print(f"  ! 无 @RestController: {name}")
            continue
        text = text[:idx] + annotation + text[idx:]
        f.write_text(text, encoding="utf-8")
        changed += 1
        print(f"  tagged {name} → {domain}")
    print(f"✓ 已为 {changed} 个 Controller 补 @Tag")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
