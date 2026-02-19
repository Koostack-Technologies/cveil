package com.koostack.operio.core.service;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.spi.Configurable;
import com.koostack.operio.core.spi.NamedPlugin;
import com.koostack.operio.core.spi.Plugin;
import com.koostack.operio.core.spi.Prioritized;
import java.util.Set;

public interface MaskingStrategy<T>
extends Plugin<T>
{
    Object mask(Object obj, Set<ConsentScope> masked);
}
