# =============================================================================
# Cveil Reverse Proxy — Nginx with environment-based upstream configuration
#
# Uses envsubst at startup to inject upstream hostnames from environment
# variables, making this image portable across Docker Compose, Railway,
# Kubernetes, etc.
#
# Required environment variables:
#   API_HOST   — hostname of the cveil-rest-api service  (default: cveil-api)
#   API_PORT   — port of the cveil-rest-api service      (default: 8080)
#   SITE_HOST  — hostname of the cveil-site service      (default: cveil-site)
#   SITE_PORT  — port of the cveil-site service          (default: 8081)
# =============================================================================

FROM nginx:alpine

# Remove the default config
RUN rm /etc/nginx/conf.d/default.conf

COPY docker/nginx/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY docker/nginx/proxy_params        /etc/nginx/proxy_params

# nginx:alpine's default entrypoint already runs envsubst on files in
# /etc/nginx/templates/ and outputs them to /etc/nginx/conf.d/.
# We just need to set the default values for our variables.
ENV API_HOST=cveil-api \
    API_PORT=8080 \
    SITE_HOST=cveil-site \
    SITE_PORT=8081

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost/healthz || exit 1
