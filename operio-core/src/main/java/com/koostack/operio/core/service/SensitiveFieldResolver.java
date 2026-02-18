package com.koostack.operio.core.service;

import java.util.Set;
import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.spi.NamedPlugin;
import com.koostack.operio.core.spi.Prioritized;

/**
 * Responsible for identifying sensitive fields within an object 
 * and mapping them to the required ConsentScopes.
 */
public interface SensitiveFieldResolver extends NamedPlugin, Prioritized {
    Set<ConsentScope> resolveScopes(Object obj);
}