ARG NODE_IMAGE=node:24.22.0-bookworm-slim
FROM ${NODE_IMAGE}
ARG SOURCE_FINGERPRINT
LABEL io.agentic.source-fingerprint="${SOURCE_FINGERPRINT}"
WORKDIR /app
RUN npm install --global pnpm@12.14.0
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm format:check && pnpm lint && pnpm typecheck && pnpm test && pnpm build
COPY docker/web.config.ts ./docker.config.ts
ENV LOCAL_DEV_IDENTITY=1 AGENTIC_BACKEND_DIR=/run/agentic
USER 10001:10001
CMD ["node", "node_modules/vite/bin/vite.js", "preview", "--config", "docker.config.ts", "--configLoader", "runner"]
