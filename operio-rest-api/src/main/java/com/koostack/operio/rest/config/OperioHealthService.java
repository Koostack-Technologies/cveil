package com.koostack.operio.rest.config;

import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.spi.Plugin;
import java.util.ArrayList;
import java.util.List;

/**
 * Application-scoped health check service that verifies readiness of all configured Operio plugins.
 *
 * <p>Replaces the former {@code TenantService.verifyReady()} dependency by holding direct
 * references to the Plugin instances that require health checks.
 */
public class OperioHealthService {

    private final List<Plugin<?>> plugins;

    public OperioHealthService(List<Plugin<?>> plugins) {
        this.plugins = List.copyOf(plugins);
    }

    /**
     * Verifies that all configured plugins are ready.
     *
     * @throws OperioConfigurationException if any plugin reports not ready
     */
    public void verifyReady() {
        List<String> notReady = new ArrayList<>();
        for (Plugin<?> plugin : plugins) {
            if (!plugin.isReady()) {
                notReady.add(plugin.getName());
            }
        }
        if (!notReady.isEmpty()) {
            throw new OperioConfigurationException(
                    "The following plugins are not ready: " + String.join(", ", notReady));
        }
    }
}
