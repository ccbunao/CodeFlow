// v2 本地演示用的 Vite 配置:仅把 /api 与 /ws 代理指向 v2(:7011),其余与主配置一致。
// 不改主配置,避免影响原有 7001 开发环境。
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 3001,
    proxy: {
      '/api': {
        target: 'http://localhost:7011',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:7011',
        ws: true,
      },
    },
  },
});
