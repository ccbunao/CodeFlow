# test-cases.md · 公众号标题优化师 · 测试用例

- 被测对象:数字员工「公众号标题优化师」(`roleCode` = `TITLE_COACH`)
- 人设来源:`deliverables/agent-prompt.md`;配置来源:`deliverables/agent-config.json`
- 平台:本机 `http://localhost:7001`(容器 `platform-app-1` + `platform-seekdb-1`)
- 分组:**A 组**平台链路用例(人设是否真的生效)/ **B 组**业务用例(标题产出质量)

---

## A 组 · 平台链路用例

### A1 人设落库并读回(FR-01)

| 项 | 内容 |
|---|---|
| 前置 | 已按 `usage-guide.md` 创建数字员工 |
| 操作 | `GET /api/agents/{id}`,观察 `soulMd` / `agentMd` |
| 期望 | `soulMd` = SOUL.md 全文,`agentMd` = AGENT.md 全文,两者非空且与 `agent-config.json` 逐字一致 |
| 落库核对 | `docker exec platform-seekdb-1 mysql -h127.0.0.1 -P2881 -uroot ainative_v2 --default-character-set=utf8mb4 -e "SELECT name, LENGTH(soul_md), LENGTH(agent_md) FROM ag_agent WHERE id=<id>"` → 两列长度均 > 0 |

### A2 编辑配置不清空人设(FR-02,原 P2 缺陷)

| 项 | 内容 |
|---|---|
| 操作 | `PUT /api/agents/{id}/config`,请求体只带 `{"roleName":"公众号标题优化师(改)"}`,不带 `soulMd` / `agentMd` |
| 期望 | `soul_md` / `agent_md` 与编辑前逐字相同;`role_name` 已更新 |
| 反例(必须失败) | 若 `soul_md` 被置空,说明「未提供」仍被当成「清空」,本用例判 FAIL |

### A3 显式清空人设(FR-02)

| 项 | 内容 |
|---|---|
| 操作 | `PUT /api/agents/{id}/config`,请求体带 `{"soulMd":"", "agentMd":""}` |
| 期望 | 两列被显式置空(空串 = 用户主动清空,与 A2 的 null 语义区分) |

### A4 派发任务包携带人设(FR-03,原 P3 缺陷)

| 项 | 内容 |
|---|---|
| 操作 | 对该数字员工派发一个需求(平台里「指派即派发」:`PUT /api/workitems/{id}/assignee`,体 `{"assigneeType":"AGENT","assigneeRef":<agentId>}`),取回任务包(`GET /api/daemon/dispatches/{dispatchId}/package?k=...`,`k` 为 `HMAC-SHA256(dispatchId.attempt, 下载密钥)` 的 base64url)并解包 `identity.json` |
| 期望 | `identity.json` 含 `soulMd`(原文)、`agentMd`(原文)、`rules`(非空数组) |
| 运行时核对 | 运行器执行 `claude --append-system-prompt "<…>"`,参数里能看到人设文本;不是只有 `name` / `roleCode` |
| 兜底核对 | 用老平台的任务包(无 `rules`)喂运行时,应回落到 `soulMd`/`agentMd` 原文,执行不中断 |

### A5 派发后改人设,包体仍可复现(FR-04)

| 项 | 内容 |
|---|---|
| 操作 | 派发后立刻编辑该数字员工的 SOUL.md,再重新下载同一 `dispatchId` 的任务包 |
| 期望 | 包体 md5 与派发帧里下发的 md5 相同,下载不失败(平台按 `rt_run.dispatch_snapshot` 重建,不读实时人设) |
| 附加核对 | `SELECT dispatch_snapshot FROM rt_run WHERE id=<dispatchId>` 非空,内含 `persona` / `ticket` / `steps` / `roster` 四段 |

### A6 澄清会话产出进需求产物区(FR-05,原 P4 缺陷)

| 项 | 内容 |
|---|---|
| 操作 | 在需求详情发起一轮 AI 澄清,等待数字员工回复 |
| 期望 1 | `as_artifact` 新增一行:`kind='CLARIFICATION'`、`ticket_id` = 该需求 id、`run_id` 为空 |
| 期望 2 | `cv_artifact` 有一行关联到该 ASSISTANT 回合(`turn_id` = `ag_chat_turn.id`) |
| 期望 3 | `GET /api/workitems/{id}/artifacts` 能查到这条产物(`name` 形如 `clarification-turn-2.md`) |
| 期望 4 | `GET /api/artifacts/{id}/download` 能下载到正文文件 |
| 核对 | `SELECT id, ticket_id, run_id, name, kind, size_bytes FROM as_artifact WHERE kind='CLARIFICATION'` |

### A7 归档产物可作为澄清附件引用(FR-06)

| 项 | 内容 |
|---|---|
| 操作 | `POST /api/workitems/{id}/clarification-conversations/{cid}/attachments`,请求体带 A6 产出的 `artifactId` |
| 期望 | 返回 200;`GET` 同一路径的附件列表能列出该产物;下一轮澄清的正文里带上 `[附件] clarification-turn-2.md` |

### A8 回归(NFR-04)

| 项 | 内容 |
|---|---|
| 内容 | server-v2 全部单测 + 集成测试通过;runtime 全部 pytest 通过;前端 `check:i18n` + `tsc -b` 通过;`docker-compose build app` 成功 |

---

## B 组 · 业务用例(标题产出质量)

三个用例分别覆盖不同文体。输入正文直接粘给数字员工,或在需求正文里写好后派发。

### B1 技术文

**输入正文**

> 我们把订单查询接口的 P99 从 1.8s 降到了 240ms。做法只有三步:给列表页加游标分页、把聚合查询挪到读缓存、再给详情接口加一层本地缓存。前两步上线当天就见效,第三步我们犹豫了两个月才做,因为担心缓存一致性。最后证明担心是多余的 —— 用版本号做失效,就没有一致性问题。

**期望输出(合格样例,仅供参考措辞)**

```text
1. 标题:P99 从 1.8s 降到 240ms
   理由:直接给出优化前后的量化对比,是技术文读者最关心的信息。
2. 标题:三步把接口延迟降了七倍
   理由:用「三步」和「七倍」把方法论与收益同时压缩进标题。
3. 标题:缓存一致性的担心中间有两个月
   理由:抓住犹豫两个月这个细节,切中同类工程师的顾虑。
4. 标题:担心缓存一致性其实是多余的
   理由:正文结论是担心多余,标题用反常识角度勾起好奇。
5. 标题:我们靠版本号解决了缓存失效
   理由:点出具体解法,让读者预期能拿到可迁移的做法。
```

**判定**

| 维度 | 通过条件 |
|---|---|
| 数量 | 恰好 5 条候选 |
| 长度 | 每条标题 ≤ 20 字(含标点) |
| 理由 | 每条一句话、≤ 40 字,说明抓的是正文哪一点 |
| 区分度 | 5 条角度不同(结论 / 方法 / 顾虑 / 反常识 / 解法),不是同义改写 |
| 事实准确 | 标题里的数字与术语都能在正文里找到(`1.8s`、`240ms`、缓存一致性、版本号) |
| 无幻觉 | 不出现正文没有的机构名、收益承诺、绝对化表述(如「彻底解决」「再也不用担心」) |
| 格式 | 无开场白、无「以下是」、无 markdown 表格、不解释思考过程 |

### B2 复盘文

**输入正文**

> 复盘一次线上事故:凌晨两点收到告警,是我们上周的发布把连接池打满了。从告警到定位用了 23 分钟,其中 15 分钟花在看监控图上 —— 因为我们的监控面板上没有连接池这个指标。这次之后我们做了三件事:把慢查询加进核心看板、发布前跑一次压测、值班手册里写清楚每个指标在哪看。

**期望输出(合格样例)**

```text
1. 标题:23 分钟定位一次连接池打满
   理由:把事故类型和定位耗时并列,复盘文读者最吃这一口。
2. 标题:定位慢的真正原因是看板缺指标
   理由:点出「看板没有连接池指标」这个根因,反直觉且可自查。
3. 标题:凌晨两点的告警值一个不响的看板
   理由:用时间与告警的错位制造张力,提醒值班同学检查监控覆盖。
4. 标题:事故后我们补了三个动作
   理由:直接给出复盘产出的三项改进,体现「复盘有结果」。
5. 标题:值班手册该写清指标在哪看
   理由:切中值班同学的操作痛点,指向正文最后一项改进。
```

**判定**:同 B1 的七个维度;另加一条 —— 标题必须体现「复盘/改进/教训」而非单纯描述事故。

### B3 观点文

**输入正文**

> 我的观点是:团队不需要更多的日报。日报能告诉你「做了什么」,但告诉不了你「做得对不对」。真正需要的是每周一次的三件事复盘:这周最值得留下的一个决定、最该改掉的一个习惯、下周要验证的一个假设。去掉日报之后,我们的信息同步反而变快了,因为大家开始说真正重要的事。

**期望输出(合格样例)**

```text
1. 标题:团队不需要更多的日报
   理由:直接亮出中心论点,观点文就该一句话说清立场。
2. 标题:日报告诉你做了什么,没告诉你做对没有
   理由:用对比句呈现观点的推理依据,制造认知落差。
3. 标题:每周三件事复盘比日报有用
   理由:给出可执行的替代方案,让读者知道看完能做什么。
4. 标题:去掉日报后同步反而更快
   理由:抓住反直觉的结果,强化观点的说服力。
5. 标题:该砍掉的不是会议是日报
   理由:把观点延展成一句更锋利的判断,适合做传播型标题。
```

**判定**:同 B1 的七个维度;另加一条 —— 5 条标题应围绕同一立场展开,不能跑偏成别的建议。

### B4 信息不足时应反问(边界用例)

**输入正文**

> 写一篇关于团队协作的文章。

**期望输出**

- 不直接给 5 个标题,或
- 先反问编辑要关键信息(写给谁 / 想让读者记住哪一点 / 文章主张是什么)

**判定**:凑数给出 5 个空泛标题 → FAIL;反问或明确说明信息不足 → PASS。

### B5 老平台兜底(兼容性用例)

**输入**:一个不含 `rules`、只有 `soulMd` / `agentMd` 的任务包(模拟旧版本平台)。

**期望**:运行时把 `soulMd` / `agentMd` 原文注入系统提示词,执行不中断,产出质量与 A4 路径一致。

---

## 判定口径汇总

| 组 | 判定方式 | 失败处理 |
|---|---|---|
| A | 真实 REST 调用 + 直查 `as_artifact` / `cv_artifact` / `rt_run` / `ag_agent` | 阻断交付 |
| B | 人工按七个维度逐条核对;长度与数量可脚本数 | 阻断交付 |

B 组「≤ 20 字」按汉字与标点计数,数字与英文单词按 1 个字计;实现上以正文字符数为准。

---

## 附:实机走查结果(2026-11-20)

在带本次改动的镜像(`platform-app:latest`)上跑完 A 组,28/28 通过:

| 用例 | 实测 | 结果 |
|---|---|---|
| A1 | `POST /api/agents` 带 `soulMd`/`agentMd` → `GET /api/agents/{id}` 原样读回;`ag_agent` 两列长度 102 / 96 | PASS |
| A2 | `PUT /api/agents/{id}/config` 只带 `roleName` → 两列逐字不变,`role_name` 已更新 | PASS |
| A3 | 同一接口传 `{"soulMd":"","agentMd":""}` → 两列置空;再传原文可恢复 | PASS |
| A4 | `PUT /api/workitems/{id}/assignee`(指派即派发)→ `rt_run.dispatch_snapshot` 非空;任务包 `identity.json` 含 `soulMd`/`agentMd` 原文与 7 条 `rules` | PASS |
| A5 | 派发后改人设再下载同一任务包 → md5 恒等(`ef2dff0d…` = `ef2dff0d…`),包内仍是派发时快照 | PASS |
| A6 | 澄清会话回话后 → `as_artifact` 新增 `kind='CLARIFICATION'`、`run_id` 为空、`name=clarification-turn-2.md`;`cv_artifact` 关联回合;产物区可查、可下载(3103 字节) | PASS |
| A7 | `POST …/clarification-conversations/{id}/attachments` 带该 `artifactId` → 200;附件列表可列出 | PASS |

走查顺带证实了两件本文件没单列的事:

- **人设确实进了会话链路**:归档正文里,运行器的回复按 `AGENT.md` 的输出契约给出了「## 5 个候选标题」——说明 `CONVERSATION_TURN` 帧里的 `systemPrompt` 带上了人设,模型是按这个员工的身份在干活。
- **澄清产物落在会话专属目录**:文件在 `<artifact-dir>/conversations/<chatId>/clarification-turn-N.md`,不与派发产物(`<artifact-dir>/<dispatchId>/`)混目录。

走查脚本在 `/tmp/e2e_persona.py`(一次性工具,未入库)。它会临时把本机在线执行器绑到测试员工上以触发真实回话,跑完即还原。

