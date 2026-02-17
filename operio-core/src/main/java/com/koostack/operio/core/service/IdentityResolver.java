package com.koostack.operio.core.service;


import com.koostack.operio.core.Models.IdentityContext;
import com.koostack.operio.core.spi.NamedPlugin;
import com.koostack.operio.core.spi.Prioritized;

public interface IdentityResolver extends NamedPlugin, Prioritized {
    IdentityContext resolve();
}
