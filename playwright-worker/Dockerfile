FROM mcr.microsoft.com/playwright:v1.64.0-noble
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY server.js network-policy.js ./
ENV PORT=8100
EXPOSE 8100
USER pwuser
CMD ["node", "server.js"]
