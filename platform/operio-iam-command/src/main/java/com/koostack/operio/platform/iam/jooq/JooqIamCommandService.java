package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.platform.iam.IamCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqIamCommandService implements IamCommandService<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqIamCommandService.class);

    @Override public String getName() { return "jooq-iam-command"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqIamCommandService configured"); }
}
