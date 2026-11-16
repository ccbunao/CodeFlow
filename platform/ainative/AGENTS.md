# Repository Agent Instructions

## 项目概览

CodeFlow 是一套开源的 AI 需求交付平台：需求澄清、流水线派发、AI 数字员工执行、产物归档、交付结论回写，全部在平台内完成。服务端在 `server-v2/`（Spring Boot 3.3，JdbcTemplate，无 ORM），前端在 `frontend/`（React 18 + Vite，随 jar 内嵌），执行器 runtime 在仓库根 `runtime/`（Python 3 标准库，零第三方依赖）。

数据库统一使用 seekdb（OceanBase，MySQL 协议兼容）。表结构见 `docs/seekdb-schema-v1-*.sql`，由 `platform/docker-compose.yml` 的 db-init 任务自动导入（106 张表）。

## 构建与验证

- 本地部署：`cd platform && ./deploy.sh up`（生成密钥 → 构建 → 起 seekdb + 应用 → 健康检查）。子命令：`down` / `restart` / `status` / `logs`。
- 后端冒烟：改了 `server-v2/src` 后，对运行中的实例跑 `server-v2/scripts/e2e_smoke.py`。
- 前端测试：`npx vitest run`（全部）或指定文件；改文案后必须同时跑 `npm run check:i18n` 和 `npx tsc -b`。
- 文档中心语料在 `frontend/public/help/content*.json`，帮助中心测试在 `frontend/src/features/help/HelpCenterPage.test.tsx`——语料和测试要一起改。

## 前端多语言规范

- 用户可见的前端文案放在 `frontend/src/shared/i18n/locales/{zh-CN,en-US}/` 下成对的语义命名空间中。在 `shared/i18n/index.ts` 中注册每个命名空间；该文件导出的 `resources` 同时定义编译期翻译键约束。React 内使用 `useTranslation(namespace)`，React 外使用导出的 `i18n` 单例。用户自行编写的名称和内容保持原样。
- 使用 `useLocale()` 获取当前语言及切换语言；`formatDateTime` 和 `formatNumber` 使用单例的当前语言；React 组件在渲染时调用格式化函数，必须通过 `useLocale` 或 `useTranslation` 订阅语言变化。
- i18next 插值占位符是双花括号（`{{name}}`）；禁止硬编码用户可见文本——`check-i18n.mjs` 会拦截。复数键采用 i18next 后缀，各语言覆盖其 Intl.PluralRules 类别，插值参数一致。
- `frontend/scripts/i18n-legacy-baseline.json` 是临时迁移债务：按文件、类型和文本精确记录出现次数。页面迁移时删除对应条目；禁止通过重新生成基线来放行新增文案，也禁止忽略整个已有页面。

## 发布与文档

- 平台版本在 `platform/ainative/VERSION`。手册版本跟随平台版本（规则见仓库根 `docs/00-开发规范与流程.md`）。
- 用户手册源文件 `docs/19-用户操作手册.md` → 排版 HTML → PDF 的生成链：`node scripts/docs-tools/manual-md2html.mjs`（md → A4 HTML）→ `node scripts/docs-tools/export-manual-pdf.mjs`（HTML → PDF）。帮助中心分发的 PDF 放 `frontend/public/help/assets/`。
- 镜像同步：`scripts/sync-mirror.sh`（内部仓 → 本仓，单向，逐字节比对共同跟踪文件）。分流文件（README、UPSTREAM 等）有意不同，脚本会跳过。

## 凭据安全

不要在任何输出、文档或提交里打印凭据。部署密钥在 `platform/.env`（`deploy.sh` 首次生成，chmod 600，已 gitignore）。`AINATIVE_SECRET_MASTER_KEY` 用于外部平台凭据加解密，更换后已保存的绑定需重新配置。
