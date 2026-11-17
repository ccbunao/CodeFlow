import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatDateTime } from '@/shared/i18n/format';
import type { RuntimeTraceEvent } from '@/shared/types/workitem';
import { Button } from 'antd';
import { aggregateRuntimeMessages } from './runtimeLogMessages';
import { detailObject } from './runtimeDetail';

const THINKING_MARKER = 'thinking';
// 语义色随主题:思考紫 / 工具蓝 / 助手绿 / 活动绿(暗色下自动提亮)
/** 步骤事件名(事件类型本身也进 i18n,避免硬编码英文落到界面)。 */
const STEP_STARTED_EVENT = 'step.started';
const STEP_COMPLETED_EVENT = 'step.completed';

/** 机器状态词:不作为正文展示。 */
const MACHINE_STATES = new Set(['completed', 'failed', 'timed_out', 'cancelled', 'started', 'success', 'error']);

/** 模型输出增量流事件:终端里折叠成一行,不逐条铺开。 */
const AGENT_PROGRESS_EVENT = 'agent.progress';

const KIND_COLORS: Record<string, string> = {
  thinking: 'var(--cf-runtime-thinking)',
  tool: 'var(--cf-runtime-tool)',
  assistant: 'var(--cf-runtime-assistant)',
  activity: 'var(--cf-runtime-assistant)',
  step: 'var(--cf-runtime-step)',
};
function decodeValue(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  try { return JSON.parse(value); } catch { return value; }
}
function renderValue(value: unknown): string {
  const decoded = decodeValue(value);
  return typeof decoded === 'string' ? decoded : JSON.stringify(decoded, null, 2);
}
function presentation(event: ReturnType<typeof aggregateRuntimeMessages>[number]) {
  // detail 可能是对象,也可能���服务端序列化后的 JSON 字符串(运行轨迹端点即如此);
  // 字符串时先解析,否则 stepName / toolName 永远取不到,标题只能退化成「步骤」。
  const d = detailObject(event.detail);
  const type = event.eventType;
  const providerType = String(d.providerType ?? d.type ?? '');
  const compact = type === 'context.compact_started' ? 'started' : type === 'context.compact_completed' ? 'completed' : null;
  const thinking = type.includes('thinking') || providerType === 'thinking';
  // 步骤事件(step.started / step.completed)—— 此前落到 activity 分支,标题统一渲染成
  // 「执行状态」,四步流水线在终端里看不出是哪一步、什么时候开始/结束。
  const stepEvent = type === STEP_STARTED_EVENT || type === STEP_COMPLETED_EVENT;
  const input = decodeValue(d.input ?? d.arguments ?? d.inputSummary);
  const params = input && typeof input === 'object' ? input as Record<string, unknown> : null;
  const tool = d.toolName ?? d.tool ?? d.name ?? params?.tool;
  const isTool = Boolean(tool) || /tool|mcp|command/.test(type) || providerType === 'tool_use';
  const output = d.output ?? d.result ?? d.outputSummary;
  // message 可能是机器状态词(completed/failed/timed_out),这类不是正文,
  // 直接铺在终端里就是一行英文噪声——过滤掉,状态由标题的 ✓ 表达。
  const rawMessage = event.messageText ?? d.content ?? d.text ?? d.message ?? d.contentSummary;
  const message = typeof rawMessage === 'string' && MACHINE_STATES.has(rawMessage.trim().toLowerCase())
    ? null : rawMessage;
  return {
    stepEvent,
    stepName: typeof d.stepName === 'string' ? d.stepName : null,
    stepOrder: typeof d.stepOrder === 'number' ? d.stepOrder : null,
    stepStarted: type === STEP_STARTED_EVENT,
    stepCompleted: type === STEP_COMPLETED_EVENT,
    compact,
    compactProvider: typeof d.provider === 'string' ? d.provider : null,
    compactAuto: d.trigger === 'auto',
    compactManual: d.trigger === 'manual',
    compactDuration: compact === 'completed' && typeof d.durationMs === 'number' ? d.durationMs : null,
    kind: stepEvent ? 'step' : thinking ? 'thinking' : isTool ? 'tool' : type === 'agent.message' ? 'assistant' : 'activity',
    tool: tool == null ? '' : renderValue(tool),
    description: params?.description,
    command: params?.command ?? d.command,
    input: params?.command != null ? null : input,
    output,
    message: message === THINKING_MARKER ? null : message,
  };
}
const TIME_OPTIONS = { dateStyle: 'medium', timeStyle: 'medium' } as const;

/** Plain text only: runtime content must never execute HTML or terminal escape commands. */
export function RuntimeTerminal({ events, query, height = 'min(65vh, 650px)' }: { events: RuntimeTraceEvent[]; query: string; height?: string }) {
  const { t } = useTranslation('workitem');
  const viewport = useRef<HTMLDivElement>(null);
  const [followOutput, setFollowOutput] = useState(true);
  const ordered = [...events].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
  const rows = aggregateRuntimeMessages(ordered).filter(event => !query || JSON.stringify(event).toLowerCase().includes(query.toLowerCase()));
  const last = ordered[ordered.length - 1];
  // agent.progress 是模型输出增量流(message=assistant chunk),逐条铺开只是噪声;
  // 折叠成一行「输出流 N 条」,展开仍可看原始事件。
  const progressCount = ordered.filter(e => e.eventType === 'agent.progress').length;
  useEffect(() => {
    if (followOutput && viewport.current) viewport.current.scrollTop = viewport.current.scrollHeight;
  }, [events, query, followOutput]);
  return <>
    <div style={{ marginBottom: 8 }}>{t('runtimeLog.lastOutput', { time: last?.eventTime ? formatDateTime(last.eventTime, TIME_OPTIONS) : t('runtimeLog.unknownTime') })}</div>
    <div ref={viewport} role="region" aria-label={t('runtimeLog.terminal')} tabIndex={0} data-testid="runtime-terminal"
      onScroll={() => { const element = viewport.current; if (element) setFollowOutput(element.scrollHeight - element.scrollTop - element.clientHeight < 32); }}
      className="cf-runtime-terminal"
      style={{ height, overflow: 'auto' }}>
      {rows.length === 0 && <div>{t('runtimeLog.terminalEmpty')}</div>}
      {progressCount > 0 && (
        <div className="cf-runtime-progress-note">
          {t('runtimeLog.progressSummary', { count: progressCount })}
        </div>
      )}
      {(() => {
        // 步骤开始与步骤完成是同一步骤的两端,合并成一条:
        // 开始时显示步骤名,完成时加 ✓;否则同一��骤会连出两行。
        const merged: typeof rows = [];
        for (const event of rows) {
          if (event.eventType === AGENT_PROGRESS_EVENT) continue;
          if (event.eventType === STEP_STARTED_EVENT || event.eventType === STEP_COMPLETED_EVENT) {
            const stepId = (detailObject(event.detail).stepId as string | undefined) ?? '';
            const prev = merged.findLast?.((m) => {
              if (m.eventType !== STEP_STARTED_EVENT && m.eventType !== STEP_COMPLETED_EVENT) return false;
              return (detailObject(m.detail).stepId as string | undefined) === stepId;
            });
            if (prev) {
              if (event.eventType === STEP_COMPLETED_EVENT) {
                prev.eventType = STEP_COMPLETED_EVENT;
                prev.detail = event.detail;
                prev.eventTime = event.eventTime ?? prev.eventTime;
                prev.endSeq = event.seq ?? prev.endSeq;
                prev.fragments = [...(prev.fragments ?? []), ...(event.fragments ?? [])];
              }
              continue;
            }
            merged.push(event);
            continue;
          }
          merged.push(event);
        }
        return merged.map(event => {
        const view = presentation(event);
        const color = KIND_COLORS[view.kind];
        const time = event.fragments?.at(-1)?.eventTime ?? event.eventTime;
        return <div key={event.eventId ?? `${event.seq}-${event.eventType}`} style={{ marginBottom: 20 }}>
          <div style={{ display: 'flex', gap: 12, alignItems: 'baseline', color }}>
            <span>
              {view.stepEvent
                ? t('runtimeLog.stepTitle', {
                  order: view.stepOrder ?? '',
                  name: view.stepName ?? t('runtimeLog.terminalKinds.step'),
                }) + (view.stepCompleted ? t('runtimeLog.stepDone') : '')
                : view.compact
                  ? t(view.compact === 'started' ? 'runtimeLog.compactStarted' : 'runtimeLog.compactCompleted')
                  : t(`runtimeLog.terminalKinds.${view.kind as 'thinking' | 'tool' | 'assistant' | 'activity' | 'step'}`)}
              {view.tool && t('runtimeLog.terminalToolName', { name: view.tool })}
            </span>
            <time className="cf-runtime-time" dateTime={time ?? undefined}>{time ? formatDateTime(time, TIME_OPTIONS) : t('runtimeLog.unknownTime')}</time>
          </div>
          {view.compact && <div className="cf-runtime-dim">
            {view.compactProvider && <div>{view.compactProvider}</div>}
            {view.compactAuto && <span>{t('runtimeLog.compactAuto')}</span>}
            {view.compactManual && <span>{t('runtimeLog.compactManual')}</span>}
            {view.compactDuration != null && <div>{t('runtimeLog.compactDuration', { milliseconds: view.compactDuration })}</div>}
          </div>}
          {view.description != null && <div className="cf-runtime-dim">{renderValue(view.description)}</div>}
          {view.command != null && <pre className="cf-runtime-block cf-runtime-command">{t('runtimeLog.terminalCommand', { command: renderValue(view.command) })}</pre>}
          {[view.message, view.input, view.output].filter(value => value != null).map((value, index) => <pre key={index} className="cf-runtime-block">{renderValue(value)}</pre>)}
          <details className="cf-runtime-details">
            <summary style={{ cursor: 'pointer' }}>{t('runtimeLog.terminalDetails')}</summary>
            <pre className="cf-runtime-block cf-runtime-json">{JSON.stringify(event.fragments ?? event, null, 2)}</pre>
          </details>
        </div>;
        });
      })()}
    </div>
    {!followOutput && <Button style={{ marginTop: 8 }} onClick={() => setFollowOutput(true)}>{t('workbench.followOutput')}</Button>}
  </>;
}
