FROM mcr.microsoft.com/playwright:v1.55.0-noble
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY server.js ./
ENV PORT=8100
EXPOSE 8100
USER pwuser
CMD ["node", "server.js"]
