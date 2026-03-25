# =============================================================================
# Cveil Keycloak — Custom themes on top of the official Keycloak image
#
# Includes the Cveil login and email themes for a branded auth experience.
#
# Required environment variables (set in Railway / docker-compose):
#   KC_DB                  — database type (postgres)
#   KC_DB_URL              — JDBC connection string
#   KC_DB_SCHEMA           — schema name (keycloak)
#   KC_DB_USERNAME         — database username
#   KC_DB_PASSWORD         — database password
#   KC_HOSTNAME            — public hostname (e.g., auth.cveil.io)
#   KC_BOOTSTRAP_ADMIN_USERNAME — initial admin username
#   KC_BOOTSTRAP_ADMIN_PASSWORD — initial admin password
# =============================================================================

FROM quay.io/keycloak/keycloak:26.0.8

# Copy custom Cveil themes
COPY docker/keycloak/themes/cveil /opt/keycloak/themes/cveil

# Build the optimized Keycloak distribution for faster startup
RUN /opt/keycloak/bin/kc.sh build \
      --db=postgres \
      --features=token-exchange,admin-fine-grained-authz,organization

EXPOSE 8080

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
