import { useTranslation } from 'react-i18next';
import { Empty } from 'antd';
import type { ReactNode } from 'react';

interface EmptyStateProps {
  description?: string;
  /** 标题(可选):有标题时是「标题 + 说明 + 操作」三段式,比光秃秃一行字友好。 */
  title?: string;
  /** 引导操作(可选):如「新建模板」按钮。 */
  action?: ReactNode;
  /** 紧凑模式:用于卡片内部,减少留白。 */
  compact?: boolean;
}

/** 统一空态:任何面板没有内容时都要有结构,不渲染空白区。 */
export function EmptyState({ description, title, action, compact }: EmptyStateProps) {
  const { t } = useTranslation('shared');
  description ??= t('empty');
  if (!title && !action) {
    return <Empty description={description} />;
  }
  return (
    <div className={`cf-empty-state${compact ? ' cf-empty-state--compact' : ''}`}>
      {title ? <div className="cf-empty-state__title">{title}</div> : null}
      <div className="cf-empty-state__desc">{description}</div>
      {action ? <div className="cf-empty-state__action">{action}</div> : null}
    </div>
  );
}
