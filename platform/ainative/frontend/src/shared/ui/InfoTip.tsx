import { QuestionCircleOutlined } from '@ant-design/icons';
import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ReactNode } from 'react';

/**
 * 术语悬浮说明:名词旁的小问号,悬停给出一句解释。
 * 用于「派发」「完成门」「记忆模式」等对新手不自解释的平台名词;
 * 纯操作按钮(含义已自明)不要加,避免问号泛滥。
 */
export function InfoTip({ text, label, placement = 'top' }: { text: ReactNode; label?: string; placement?: 'top' | 'bottom' | 'left' | 'right' }) {
  const { t } = useTranslation('shared');
  const aria = label ? `${label} · ${t('infoTip.aria')}` : t('infoTip.aria');
  return (
    <Tooltip title={text} placement={placement} mouseEnterDelay={0.15}>
      <QuestionCircleOutlined
        aria-label={aria}
        role="img"
        tabIndex={0}
        onFocus={(e) => { e.currentTarget.style.color = 'var(--cf-accent-text, #2383e2)'; }}
        onBlur={(e) => { e.currentTarget.style.color = ''; }}
        className="cf-infotip"
        style={{ color: 'var(--cf-muted, #8a94a4)', fontSize: 12, marginLeft: 4, cursor: 'help', verticalAlign: 'middle' }}
      />
    </Tooltip>
  );
}
