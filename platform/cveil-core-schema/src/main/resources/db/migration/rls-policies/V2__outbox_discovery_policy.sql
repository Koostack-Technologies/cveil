-- ============================================================================
-- Outbox poller discovery policy
-- ============================================================================
-- Allows the outbox poller to discover which tenants have unpublished events
-- by querying SELECT DISTINCT tenant_id FROM event WHERE published = false.
--
-- The poller sets app.tenant_id = '__discovery__' (a reserved sentinel that
-- is never a real tenant ID) before running the discovery query.  This
-- SELECT-only policy grants visibility to all rows when the sentinel is
-- active, while real tenant isolation remains enforced by the existing
-- tenant_isolation policy.
-- ============================================================================

CREATE POLICY outbox_discovery ON cveil_event.event
    FOR SELECT
    USING (current_setting('app.tenant_id', true) = '__discovery__');
