package com.koostack.operio.core.service;

import com.koostack.operio.core.Models.IdentityContext;
import com.koostack.operio.core.spi.Configurable;
import com.koostack.operio.core.spi.NamedPlugin;
import com.koostack.operio.core.spi.Plugin;
import com.koostack.operio.core.spi.Prioritized;

public interface IdentityResolver<T>
extends Plugin<T>
{
    IdentityContext resolve();
}
