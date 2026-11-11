FROM node:24-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html/dashboard

RUN mkdir -p /tmp/nginx /var/cache/nginx /var/log/nginx \
    && chown -R nginx:nginx /usr/share/nginx/html /tmp/nginx /var/cache/nginx /var/log/nginx

EXPOSE 8080

USER nginx

CMD ["nginx", "-g", "daemon off;"]
