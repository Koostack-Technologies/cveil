package com.koostack.operio.core.service;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.spi.Plugin;

import java.util.Set;

public interface MaskingStrategy<T>
extends Plugin<T>
{
    Object mask(Object obj, Set<ConsentScope> masked);
}
