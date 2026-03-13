package com.koostack.cveil.core.sdk;

import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.core.spi.Plugin;

public interface IdentityResolver<T> extends Plugin<T> {
    IdentityContext resolve();
}
