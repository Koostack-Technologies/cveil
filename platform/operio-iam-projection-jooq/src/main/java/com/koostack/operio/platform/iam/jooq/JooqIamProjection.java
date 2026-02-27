package com.koostack.operio.platform.iam.jooq;

import com.koostack.operio.core.platform.iam.IamProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqIamProjection implements IamProjection<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqIamProjection.class);

    @Override public String getName() { return "jooq-iam-projection"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<Void> getConfigurationType() { return Void.class; }
    @Override public void configure(Void config) { LOG.info("JooqIamProjection configured"); }
}
