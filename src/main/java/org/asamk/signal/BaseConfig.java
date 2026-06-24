package org.asamk.signal;

public class BaseConfig {

    public static final String PROJECT_NAME = BaseConfig.class.getPackage().getImplementationTitle();
    public static final String PROJECT_VERSION = BaseConfig.class.getPackage().getImplementationVersion();
    public static final String USER_AGENT_ENV = "SIGNAL_CLI_USER_AGENT";

    private BaseConfig() {
    }

    public static String getDefaultUserAgent() {
        return "Signal-Android/8.15.0 " + getSignalCliUserAgentSuffix();
    }

    /**
     * Resolves the User-Agent string. Priority: CLI/config value, then {@value #USER_AGENT_ENV} env var, then default.
     */
    public static String resolveUserAgent(final String configuredUserAgent) {
        if (configuredUserAgent != null && !configuredUserAgent.isBlank()) {
            return configuredUserAgent.trim();
        }
        final var envUserAgent = System.getenv(USER_AGENT_ENV);
        if (envUserAgent != null && !envUserAgent.isBlank()) {
            return envUserAgent.trim();
        }
        return getDefaultUserAgent();
    }

    private static String getSignalCliUserAgentSuffix() {
        return PROJECT_NAME == null ? "signal-cli" : PROJECT_NAME + "/" + PROJECT_VERSION;
    }
}
