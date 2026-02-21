package com.koostack.operio.core.service;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.spi.Plugin;

public interface ConsentAdminService<T> extends Plugin<T> {
    void grantConsent(String subject, ConsentScope accessRequest);
    void revokeConsent(String subject, ConsentScope accessRequest);
}
