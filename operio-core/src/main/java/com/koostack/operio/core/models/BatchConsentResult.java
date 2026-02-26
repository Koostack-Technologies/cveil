package com.koostack.operio.core.models;

import java.util.List;
/**
 * The result of a batch consent operation (grant or revoke).
 *
 * <p>Each item in the batch is independently atomic — partial success is
 * possible. Failed items are captured in {@link #errors} with the
 * original index, scope, and error message.
 *
 * @param succeeded the number of items that completed successfully
 * @param failed    the number of items that failed
 * @param errors    details for each failed item, possibly empty
 */
public record BatchConsentResult(
    int succeeded,
    int failed,
    List<BatchItemError> errors
) {
    /**
     * Describes a single failed item within a batch consent operation.
     *
     * @param index   the zero-based index of the failed item in the
     *                original request list
     * @param scope   the consent scope that failed
     * @param message a human-readable error message
     */
    public record BatchItemError(
        int index,
        ConsentScope scope,
        String message
    ) {}
}
