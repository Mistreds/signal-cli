package org.asamk.signal;

public class BaseConfig {

    public static final String PROJECT_NAME = BaseConfig.class.getPackage().getImplementationTitle();
    public static final String PROJECT_VERSION = BaseConfig.class.getPackage().getImplementationVersion();
    public static final String USER_AGENT_ENV = "SIGNAL_CLI_USER_AGENT";

    private BaseConfig() {
    }

    /**
     * Resolves an explicit User-Agent. Priority: CLI/config value, then {@value #USER_AGENT_ENV}.
     *
     * @return the configured agent, or {@code null} when each device should use its own Signal Android or iOS agent
     */
    public static String resolveUserAgent(final String configuredUserAgent) {
        if (configuredUserAgent != null && !configuredUserAgent.isBlank()) {
            return configuredUserAgent.trim();
        }
        final var envUserAgent = System.getenv(USER_AGENT_ENV);
        if (envUserAgent != null && !envUserAgent.isBlank()) {
            return envUserAgent.trim();
        }
        return null;
    }
}
