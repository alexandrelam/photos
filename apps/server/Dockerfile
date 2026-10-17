# Build from the repository root: docker build -f apps/server/Dockerfile .
FROM oven/bun:1.4

# ffmpeg gives videos a thumbnail; everything else works without it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json bun.lock ./
COPY apps/server/package.json apps/server/
RUN bun install --frozen-lockfile --production

COPY apps/server apps/server
WORKDIR /app/apps/server

ENV PHOTOS_ROOT=/photos \
    DATA_DIR=/data \
    PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
    CMD bun -e "fetch('http://localhost:8080/api/health').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"

CMD ["bun", "src/index.ts"]
