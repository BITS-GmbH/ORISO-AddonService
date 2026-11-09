FROM node:22-alpine
WORKDIR /app
COPY server.mjs ./
COPY ui ui
USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
