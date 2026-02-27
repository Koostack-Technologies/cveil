package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.platform.iam.IamQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqIamQueryService implements IamQueryService<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqIamQueryService.class);

    @Override public String getName() { return "jooq-iam-query"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqIamQueryService configured"); }
}
