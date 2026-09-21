FROM node:22.23.2-alpine3.23 AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . ./
RUN npm run build

FROM nginx:1.30.5-alpine3.24
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.revision=$SOURCE_REVISION
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
