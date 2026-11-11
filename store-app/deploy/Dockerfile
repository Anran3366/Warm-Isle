FROM node:22-alpine

WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci --omit=dev --registry=https://registry.npmmirror.com || npm install --omit=dev --registry=https://registry.npmmirror.com

COPY src ./src
COPY admin ./admin

ENV STORE_HOST=0.0.0.0 \
    PORT=3500 \
    STORE_DATA_DIR=/data

EXPOSE 3500

CMD ["node", "src/server.js"]
