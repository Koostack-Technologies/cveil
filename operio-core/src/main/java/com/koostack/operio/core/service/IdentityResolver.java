package com.koostack.operio.core.service;

import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.spi.Plugin;

public interface IdentityResolver<T> extends Plugin<T> {
    IdentityContext resolve();
}
