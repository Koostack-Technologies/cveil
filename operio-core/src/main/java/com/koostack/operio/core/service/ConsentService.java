package com.koostack.operio.core.service;

import com.koostack.operio.core.spi.NamedPlugin;
import com.koostack.operio.core.spi.Prioritized;

public interface ConsentService extends NamedPlugin, Prioritized {
    // Determines if consent is present for the given category
    boolean isConsentGiven(String category);
}