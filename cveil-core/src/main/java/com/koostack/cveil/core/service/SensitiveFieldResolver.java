package com.koostack.cveil.core.service;

import com.koostack.cveil.core.models.ConsentScope;
import com.koostack.cveil.core.spi.Plugin;

import java.util.Set;

/**
 * Responsible for identifying sensitive fields within an object
 * and mapping them to the required ConsentScopes.
 */
public interface SensitiveFieldResolver<T>
extends Plugin<T>
{
    Set<ConsentScope> resolveScopes(Object obj);
}
