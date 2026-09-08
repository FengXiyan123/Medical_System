FROM node:22-alpine AS build

WORKDIR /app
COPY vue/package.json vue/package-lock.json ./
RUN npm ci
COPY vue .
RUN npm run build

FROM nginx:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY infra/nginx/default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
