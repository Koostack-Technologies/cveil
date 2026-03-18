-- ============================================================================
-- V6: Performance indexes for DSAR queries
-- ============================================================================

-- Index for DsarResource.listRequests() which sorts by created_at DESC
-- and always filters by tenant_id first.
CREATE INDEX IF NOT EXISTS idx_dsar_request_tenant_created
    ON dsar_request(tenant_id, created_at DESC);

-- Index for DsarResource.getActivity() which joins on request_uuid
-- and sorts by created_at DESC.
CREATE INDEX IF NOT EXISTS idx_dsar_activity_request_created
    ON dsar_activity_log(request_uuid, created_at DESC);

-- Index for duplicate detection queries (both admin and public endpoints)
-- which filter by (tenant_id, requester_email, request_type, created_at).
CREATE INDEX IF NOT EXISTS idx_dsar_request_dedup
    ON dsar_request(tenant_id, requester_email, request_type, created_at DESC);
