package com.koostack.operio.core.metrics;

import com.koostack.operio.core.spi.Plugin;

/**
 * @deprecated Use {@link MetricPublisherService} instead. This interface will be removed in a
 *     future release.
 */
@Deprecated(forRemoval = true)
public interface PlatformMetricsService<T> extends Plugin<T> {}
