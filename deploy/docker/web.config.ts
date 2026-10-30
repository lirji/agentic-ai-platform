import { defineConfig } from 'vite';
import { localIdentityBridge, LOCAL_PORT } from './deploy/local-identity';

// 本地容器沿用同源校验和短时身份签发，直接提供已经构建的 dist。
const identity = localIdentityBridge();
export default defineConfig({
  plugins: [{ ...identity, configurePreviewServer: identity.configureServer }],
  preview: {
    host: '0.0.0.0',
    port: LOCAL_PORT,
    strictPort: true,
    cors: false,
    proxy: Object.fromEntries(
      [
        '/api/v2/knowledge', '/api/v1/knowledge',
        '/api/v2/conversations', '/api/v1/conversations',
        '/api/v2/tasks', '/api/v1/tasks',
      ].map(
        (path) => [path, {
          target: 'http://127.0.0.1:28080',
          changeOrigin: true,
          timeout: 75000,
          proxyTimeout: 75000,
        }],
      ),
    ),
  },
});
