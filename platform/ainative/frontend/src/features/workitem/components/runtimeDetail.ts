/** 运行时事件的 detail 归一化。
 *
 * 服务端在不同端点返回的 detail 形态不一致:
 * - 运行轨迹端点(rt_event.detail_json)是 JSON 字符串;
 * - WebSocket 推送的事件是已解析的对象。
 * 前端直接按对象取字段时,字符串分支一律取不到(stepName / toolName / reason 全丢),
 * 表现为标题退化成「步骤」、失败原因空白。这里统一成对象。
 */
export function detailObject(detail: unknown): Record<string, unknown> {
  if (detail && typeof detail === 'object') return detail as Record<string, unknown>;
  if (typeof detail === 'string') {
    try {
      const parsed = JSON.parse(detail);
      return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : {};
    } catch {
      return {};
    }
  }
  return {};
}
