package com.koostack.operio.core.service;

import java.util.Set;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.spi.NamedPlugin;
import com.koostack.operio.core.spi.Prioritized;

public interface MaskingStrategy extends NamedPlugin, Prioritized  {
    Set<ConsentScope> extractConsentScopes(Object obj);
    Object mask(Object obj, Set<ConsentScope> masked);
}
