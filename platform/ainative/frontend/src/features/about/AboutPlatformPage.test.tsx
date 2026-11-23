import { describe, expect, it } from 'vitest';
import type { ReactElement } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { AboutPlatformPage } from './AboutPlatformPage';

function renderWithQueryClient(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<MemoryRouter><QueryClientProvider client={queryClient}>{ui}</QueryClientProvider></MemoryRouter>);
}

describe('AboutPlatformPage', () => {
  it('shows the independent delivery story and removes the old onboarding content', () => {
    renderWithQueryClient(<AboutPlatformPage />);

    expect(screen.getByRole('region', { name: '关于平台' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '软件产品自动交付平台' })).toBeInTheDocument();
    expect(screen.getByText('从需求到交付的一站式 AI 交付平台')).toBeInTheDocument();
    // 「交付 9 号线」地铁图:线路上只放「编号 + 站名」短标记,
    // 九句解释另起「站序说明」栏(线路图站间距放不下长句,会压到相邻站牌溢出卡片)。
    const items = screen.getAllByRole('listitem').map((item) => item.textContent);
    expect(items.slice(0, 9)).toEqual([
      '1问清需求', '2启动交付', '3编码开发', '4测试验证', '5代码评审',
      '6部署上线', '7上线回归', '8验收归档', '9持续运维',
    ]);
    expect(items.slice(9)).toEqual([
      '1问清需求你提需求,AI 反问到能开工为止:边界、技术栈、验收标准逐条确认,产出方案文档',
      '2启动交付你点「启动交付」,任务包下发到你的电脑:AI 在你的机器上开工,代码不出内网',
      '3编码开发AI 按方案文档写代码:每个分支对应一个需求,改了什么、为什么改,全程有记录',
      '4测试验证AI 跑通测试才算写完:单测、自测脚本、验收标准逐条自动校验,不达标停在当前步并说明原因',
      '5代码评审评审员检查通过才放行:产物完整性、完成门达标逐项核对,不通过打回返工',
      '6部署上线可运行产物直接部署:代码、启动/停止脚本、部署路径一并交付,拿来就能跑',
      '7上线回归上线前自动化回归:验证整条链路在真实环境可用,回归不通过不放行',
      '8验收归档你验收,结论自动回写:8 个产物与交付报告归档进工单,验收结论写回禅道/TAPD',
      '9持续运维上线后继续盯着:运行记录可查,新需求/缺陷进来就是下一轮迭代',
    ]);
    expect(screen.getByText('交付 9 号线 · 一单一趟,全程有站')).toBeInTheDocument();
    expect(screen.getByText('全程 9 站 · 全程留痕,产物归档')).toBeInTheDocument();
    expect(screen.getByText('持续迭代')).toBeInTheDocument();
    expect(screen.queryByText('需求系统集成')).not.toBeInTheDocument();
    expect(screen.queryByText('5 分钟理解工作流')).not.toBeInTheDocument();
  });

  it('shows the default deployment version placeholder', async () => {
    renderWithQueryClient(<AboutPlatformPage />);

    await waitFor(() => {
      expect(screen.getByText('x.x.x')).toBeInTheDocument();
    });
  });

  it('shows the configured deployment version from public branding', async () => {
    server.use(
      http.get('/api/platform/branding/public', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: {
            platformName: 'CodeFlow',
            logoUrl: '/logo.png',
            themeKey: 'ocean-blue',
            primaryColor: '#f97316',
            domain: 'https://community.example',
            mcpBaseUrl: 'https://community.example/api/mcp',
            recommendedRuntimeVersion: '0.2.125',
            deploymentVersion: '1.2.3',
            canManage: false,
          },
          traceId: 'trace-branding',
        });
      }),
    );

    renderWithQueryClient(<AboutPlatformPage />);

    await waitFor(() => {
      expect(screen.getByText('1.2.3')).toBeInTheDocument();
    });
  });

  it('falls back to the placeholder when branding returns an empty deployment version', async () => {
    server.use(
      http.get('/api/platform/branding/public', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: {
            platformName: 'CodeFlow',
            logoUrl: '/logo.png',
            themeKey: 'ocean-blue',
            primaryColor: '#f97316',
            domain: 'https://community.example',
            mcpBaseUrl: 'https://community.example/api/mcp',
            recommendedRuntimeVersion: '0.2.125',
            deploymentVersion: '   ',
            canManage: false,
          },
          traceId: 'trace-branding',
        });
      }),
    );

    renderWithQueryClient(<AboutPlatformPage />);

    await waitFor(() => {
      expect(screen.getByText('x.x.x')).toBeInTheDocument();
    });
  });

  it('still renders the deployment version when the branding request fails', async () => {
    server.use(
      http.get('/api/platform/branding/public', () => {
        return HttpResponse.json({ success: false, code: '500', message: 'boom' }, { status: 500 });
      }),
    );

    renderWithQueryClient(<AboutPlatformPage />);

    expect(screen.getByText('x.x.x')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText('软件产品自动交付平台')).toBeInTheDocument();
    });
  });
});
