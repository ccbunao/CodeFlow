# CodeFlow

**AI Native 软件需求交付平台 + 开源执行器运行时。** 需求进、交付出:数字员工按交付流程自动完成分析、开发、测试、验证与交接,人只做需求澄清与验收。

仓库:https://github.com/qsumm/CodeFlow

## 核心能力

### 数字员工交付流程
- **小队 / 数字员工 / 交付流程模板**:分析与分支准备 → 编码与测试 → 验证与交付 → 交接评审,多角色自动接棒;失败留痕、attempt 递增、可重新指派重启
- **需求澄清对话**:在工单里直接与数字员工对话;数字员工经平台 MCP **实时读取工单**(标题/状态/外部来源)后作答与追问
- **交付结论回写**:交付报告经备注通道回写源头工单平台,幂等 marker 防重复回写

### Agent 客户端(6 种,开箱即用)
`Claude Code` · `Codex CLI` · `Cursor CLI` · `Gemini CLI` · `Qoder CLI` · `Qoder CLI CN`

### 模型接入
任意 Anthropic 兼容端点:Claude / MiMo / GLM / Kimi / DeepSeek / 私有模型(`ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN` 即接)

### 外部工单平台
- **禅道 ZenTao 18.10+**:绑定产品 → 需求/缺陷自动拉取入库 → 派发数字员工 → 交付备注回写源头。**已真实闭环验证**(建需求 → 开发交付 → 建缺陷 → 修复 → 回写,全程记录见 `docs/18`)
- Provider 抽象:绑定/映射/回执全 provider 无关,新平台只需实现 `ExternalWorkitemProvider`

### 平台功能
工单管理与交付进度跟踪 · 小队/交付流程/仓库/技能配置 · MCP 令牌与工具 · IM 机器人(钉钉/飞书) · 平台品牌主题 · 帮助中心 · 审计与洞察 · 暂停/恢复与执行控制

### 执行器 runtime(本仓库 `runtime/`,纯标准库)
- 心跳与能力声明、任务包下载(sha256 校验)、交付流程逐步执行、澄清会话(`CONVERSATION_TURN` 协议)、交付备注上报
- 执行器离线排队走指数退避、上线自动唤醒;同一 executorId 单实例(服务端单会话,后来者顶掉先来者)

## 快速开始(克隆到运行)

**A. 平台前后端**(v2:`platform/ainative/server-v2`,数据库统一 seekdb):

```bash
cd platform
./deploy.sh up           # 生成密钥 → 构建 → 起 seekdb + 应用 → 等待就绪
# 打开 http://localhost:7001/register 注册首个用户(建组织即成为管理员)
```

`deploy.sh` 首次运行会生成 `platform/.env`(JWT 密钥、凭据加密主密钥、下载密钥,chmod 600),
之后 `down` / `restart` / `status` / `logs` 可用;端口可覆盖:`APP_PORT=8080 ./deploy.sh up`。

最低配置:单机 4C8G(2G 内存跑不动全栈);纯执行器节点 2C2G 即可。
密钥说明:两个密钥首次部署时生成并妥善保存。**换环境部署会产生新密钥,旧库加密数据不可迁移**;
`AINATIVE_SECRET_MASTER_KEY` 用于外部平台(TAPD 等)凭据的加解密,多节点必须一致,更换后需重新保存绑定。

**B. 执行器 runtime**(连上面任一平台):

```bash
pip install -e .                        # 或直接 python -m runtime.daemon(纯标准库)

# 1. 注册执行器(在平台 Agents 页找到数字员工 ID 与工作空间 ID)
export AINATIVE_BASE_URL=http://localhost:7001
export AINATIVE_ADMIN_USER=admin
export AINATIVE_ADMIN_PASSWORD=...
python scripts/register_executor.py --agent-id 10001 --workspace-id 10000 --out runtime.env --provider claude

# 2. 补充模型通道(env 文件追加)
echo 'AINATIVE_AGENT_CMD=claude' >> runtime.env
echo 'ANTHROPIC_BASE_URL=https://<anthropic-compatible-endpoint>' >> runtime.env
echo 'ANTHROPIC_AUTH_TOKEN=<your-token>' >> runtime.env
echo 'ANTHROPIC_MODEL=<model-id>' >> runtime.env

# 3. 自检 + 常驻
set -a; . ./runtime.env; set +a
ainative --check-config && ainative --once-smoke
ainative                                    # 或 python -m runtime.daemon,常驻等待派发
```

约束:**同一个 executorId+token 同时只允许一个 daemon 实例**(服务端单会话,后来者顶掉先来者)。

## 外部工单闭环(禅道)怎么跑

```bash
# 1) 平台侧:平台集成页签绑定禅道产品(或 REST POST /api/integrations/zentao/bindings)
# 2) 构造数据 + 观察(执行全部交给平台与数字员工):
export ZENTAO_ACCOUNT=... ZENTAO_PASSWORD=... ZENTAO_PRODUCT_ID=1
python scripts/zentao_e2e.py create-story --title "..." --spec "..."   # 禅道建需求
python scripts/zentao_e2e.py wait-import --external-prefix story-      # 等平台拉取入库
python scripts/zentao_e2e.py assign --workitem-id <id>                 # 指派数字员工,触发派发
python scripts/zentao_e2e.py wait-done --workitem-id <id>              # 只读观察交付
python scripts/zentao_e2e.py verify-writeback --kind story --object-id <id>  # 校验回写
```

完整过程与结论见 [`docs/18-禅道闭环E2E总结报告`](docs/18-禅道闭环E2E总结报告.md)。

## 架构与兼容性策略

- **平台源码**:`platform/ainative/` 包含服务端与前端完整实现,MIT `LICENSE` 随分发保留(见 [`UPSTREAM.md`](platform/ainative/UPSTREAM.md));
- **执行器 runtime**:`runtime/` 是独立进程,只通过 WebSocket 协议帧与平台交互,不引用平台代码;
- 协议兼容由**单一兼容层**兜住:编解码集中在 `runtime/frames.py` + `runtime/packager.py`,协议字段或任务包 schema 变化只改这两个文件;
- 升级验收:`pytest` 全量 + 对新服务端 `--once-smoke`;服务端行为变化先实测再改兼容层,严禁凭文档盲改;
- 契约回归:测试文档 L2/L3 用例即验收清单。

## 状态

- **已完成**:P0 全链路(docs/09)、执行控制与暂停恢复、禅道接入与闭环 E2E(docs/17/18)、需求澄清会话协议、品牌与命名规范(docs/12 BL-16/17)
- **进行中/路线**:P1 人工门禁问答、handoff 产物、会话恢复增强、artifact 上传;遗留项见[待办台账](docs/12-待办台账.md)

## 文档

- **团队规范(长期有效)**:[docs/00-开发规范与流程](docs/00-开发规范与流程.md) · 缺陷必须登记到 [docs/07-缺陷修复记录](docs/07-缺陷修复记录.md)
- **待办台账**:[docs/12-待办台账](docs/12-待办台账.md)(BL-01 暂停/恢复协议、BL-05 凭据治理、BL-11 MinIO 公网 endpoint…)
- 工程文档:[docs/01-立项说明](docs/01-立项说明.md) · [02-需求文档](docs/02-需求文档-PRD.md) · [03-设计文档](docs/03-设计文档.md) · [04-概要设计/接口/数据库](docs/04-概要设计与接口-数据库.md) · [05-测试文档](docs/05-测试文档.md) · [06-测试报告](docs/06-测试报告.md)
- 集成:[17-禅道接入](docs/17-外部工单平台多源支持-禅道.md) · [18-禅道闭环E2E总结](docs/18-禅道闭环E2E总结报告.md)
- **用户操作手册**:[docs/19-用户操作手册](docs/19-用户操作手册.md)(图文版,面向平台使用者)
