import { defineConfig } from 'vite';
import { localIdentityBridge, LOCAL_PORT } from './deploy/local-identity';

// 模式与构建时一致；正式登录不挂载身份签名私钥，也不启用本地身份桥。
const mode = process.env.VITE_IDENTITY_MODE ?? 'local';
if (!['local', 'oidc'].includes(mode)) throw new Error('Unsupported identity mode');
process.env.LOCAL_DEV_IDENTITY = mode === 'local' ? '1' : '0';
const identity = mode === 'local' ? localIdentityBridge() : undefined;
export default defineConfig({
  plugins: identity ? [{ ...identity, configurePreviewServer: identity.configureServer }] : [],
  preview: {
    host: '0.0.0.0',
    port: LOCAL_PORT,
    strictPort: true,
    cors: false,
    proxy: Object.fromEntries(
      (mode === 'oidc' ? ['/api', '/oauth2/authorization', '/login/oauth2/code'] : [
        '/api/v2/knowledge', '/api/v1/knowledge',
        '/api/v3/conversations', '/api/v2/conversations', '/api/v1/conversations',
        '/api/v4/tasks', '/api/v3/tasks', '/api/v2/tasks', '/api/v1/tasks',
        '/api/v1/usage',
      ]).map(
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
