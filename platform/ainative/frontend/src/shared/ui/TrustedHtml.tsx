import type { ReactNode } from 'react';

/**
 * 受控富文本渲染:仅允许 i18n 文案中的 <mark> 与 <b> 两种标记,
 * 转成 React 元素而不是 dangerouslySetInnerHTML(无 XSS 面)。
 * 其它任何标签一律按纯文本保留——新增标记类型时必须在这里显式放行。
 */
export function renderTrusted(html: string): ReactNode[] {
  const parts = html.split(/<\/(mark|b)>|<(mark|b)>/g);
  // split 捕获组:偶数段为文本,奇数段为出现的闭合/开标记名
  const out: ReactNode[] = [];
  let mark = false;
  let bold = false;
  for (const part of parts) {
    if (part === undefined) continue;
    if (part === 'mark') { mark = !mark; continue; }
    if (part === 'b') { bold = !bold; continue; }
    if (!part) continue;
    if (mark) out.push(<mark key={out.length}>{part}</mark>);
    else if (bold) out.push(<strong key={out.length}>{part}</strong>);
    else out.push(<span key={out.length}>{part}</span>);
  }
  return out;
}

export function TrustedHtml({ html }: { html: string }) {
  return <>{renderTrusted(html)}</>;
}
