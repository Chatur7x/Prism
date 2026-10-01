# =============================================================================
# PRISM frontend image
# =============================================================================
# The built output is static files. It is served by nginx rather than by a Node
# server, because running node in production to hand over a few hashed assets is
# a needless runtime attack surface and a needless memory cost.
#
# The API is reverse-proxied from the same origin (see docker/nginx/
# prism-frontend.conf). That is not a convenience: the SSE debate stream and the
# CORS rules are both simpler and more correct when the browser only ever talks
# to one origin, and it means the frontend needs no build-time API URL at all.
#
# BUILD CONTEXT IS THE REPOSITORY ROOT, not ./frontend, because the nginx
# configuration lives at docker/nginx/ and has to be copied in. The root
# .dockerignore keeps the context small by excluding the backend, node_modules,
# and every local artefact.
# =============================================================================

# ---- build -----------------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /build

# Same reasoning as the backend: dependencies are a layer of their own, keyed on
# the lockfile, so source edits do not re-run the installer.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/tsconfig.json frontend/vite.config.ts frontend/index.html ./
COPY frontend/src ./src

# The build script runs `tsc -b && vite build`, so a type error fails the image
# build rather than shipping a bundle that will throw at runtime. There is one
# tsconfig and no project references, so only it needs copying.
RUN npm run build

# ---- runtime ---------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

# The config and the built assets are copied in; the node toolchain and the
# source are not, so none of it exists in the shipped image.
COPY --from=build /build/dist /usr/share/nginx/html
COPY docker/nginx/prism-frontend.conf /etc/nginx/conf.d/default.conf

# nginx's own healthcheck, probing the static root rather than the API. A
# frontend that is up while the backend restarts is still a healthy frontend,
# and restarting it would accomplish nothing.
#
# 127.0.0.1 rather than localhost: nginx's default `listen 80` binds IPv4 only,
# while `localhost` resolves to ::1 first inside the container. Probing the
# name therefore always got connection refused and reported the frontend
# permanently unhealthy, which would also gate anything depending on it.
HEALTHCHECK --interval=15s --timeout=4s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1

EXPOSE 80
