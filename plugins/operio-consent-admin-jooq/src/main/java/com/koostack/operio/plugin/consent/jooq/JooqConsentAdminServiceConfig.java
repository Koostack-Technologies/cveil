package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.jooq.common.JooqPluginConfig;

public record JooqConsentAdminServiceConfig(
    String connectionString,
    String dialect
) implements JooqPluginConfig {}
