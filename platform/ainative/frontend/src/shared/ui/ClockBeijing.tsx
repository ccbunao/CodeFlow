import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import './ClockBeijing.css';

/**
 * 顶栏北京时间:恒定 Asia/Shanghai,与用户本地时区/语言无关,
 * 便于 distributed 团队对齐"平台时间"(日志、审计里都是平台本地时钟)。
 */
export function ClockBeijing() {
  const { t } = useTranslation('common');
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const fmt = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false,
  });
  // zh-CN 数字部分形如 "2026/11/17 01:23:45";统一为 - 分隔便于扫读
  const text = fmt.format(now).replace(/\//g, '-');

  return (
    <time className="wb-clock" dateTime={text} title={t('navigation.beijingTime')} data-testid="beijing-clock">
      <span className="wb-clock-label">{t('navigation.beijingTime')}</span>
      <span className="wb-clock-value">{text}</span>
    </time>
  );
}
