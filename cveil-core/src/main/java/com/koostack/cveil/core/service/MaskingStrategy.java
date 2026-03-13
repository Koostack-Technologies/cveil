package com.koostack.cveil.core.service;

import com.koostack.cveil.core.models.ConsentScope;
import com.koostack.cveil.core.spi.Plugin;

import java.util.Set;

public interface MaskingStrategy<T>
extends Plugin<T>
{
    Object mask(Object obj, Set<ConsentScope> masked);
}
