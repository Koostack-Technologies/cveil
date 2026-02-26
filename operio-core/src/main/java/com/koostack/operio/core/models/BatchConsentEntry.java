package com.koostack.operio.core.models;

import java.time.Instant;
/**
 * A single entry in a batch consent grant or revoke request.
 *
 * @param scope     the consent scope to grant or revoke
 * @param expiresAt when the consent expires, or {@code null} for no
 *                  expiration
 */
public record BatchConsentEntry(ConsentScope scope, Instant expiresAt) {}
