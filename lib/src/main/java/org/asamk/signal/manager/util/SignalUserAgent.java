package org.asamk.signal.manager.util;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * User-Agent strings copied from the official clients.
 * <p>
 * Android ({@code StandardUserAgentInterceptor}):
 * {@code Signal-Android/} + {@code BuildConfig.VERSION_NAME} + {@code Android/} + {@code Build.VERSION.SDK_INT}.
 * iOS ({@code HttpHeaders.userAgentHeaderValueSignalIos}):
 * {@code Signal-iOS/} + {@code AppVersionImpl.currentAppVersion} + {@code iOS/} + {@code UIDevice.systemVersion}.
 * {@code currentAppVersion} is the release tag, for example {@code 8.38.0.2011}.
 */
public final class SignalUserAgent {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** versionName values from recent Signal-Android releases. */
    private static final String[] ANDROID_VERSIONS = {
            "8.36.0",
            "8.36.1",
            "8.36.2",
            "8.36.3",
            "8.37.0",
            "8.37.1",
            "8.37.2"
    };
    /** SDK levels real devices report. Signal-Android targets 36 and compiles against 37. */
    private static final int[] ANDROID_SDK = {34, 35, 36, 37};

    /** Release tags from Signal-iOS. This is AppVersionImpl.currentAppVersion. */
    private static final String[] IOS_APP_VERSIONS = {
            "8.31.0.1895",
            "8.32.0.1910",
            "8.33.0.1925",
            "8.34.0.1942",
            "8.35.0.1954",
            "8.35.1.1964",
            "8.36.0.1981",
            "8.36.1.1988",
            "8.37.0.1993",
            "8.38.0.2011"
    };
    /** UIDevice.current.systemVersion on current iOS releases. */
    private static final String[] IOS_SYSTEM = {
            "26.0",
            "26.0.1",
            "26.1",
            "26.1.1",
            "26.2",
            "26.2.1",
            "26.3",
            "26.4",
            "26.5",
            "27.0",
            "27.0.1",
            "27.1"
    };

    private static final Set<String> KNOWN = knownAgents();

    private SignalUserAgent() {
    }

    public static String random() {
        return RANDOM.nextBoolean() ? randomAndroid() : randomIos();
    }

    public static boolean isOfficialDeviceUserAgent(final String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        if (userAgent.toLowerCase(Locale.ROOT).contains("signal-cli")) {
            return false;
        }
        return KNOWN.contains(userAgent);
    }

    private static String randomAndroid() {
        return android(pick(ANDROID_VERSIONS), ANDROID_SDK[RANDOM.nextInt(ANDROID_SDK.length)]);
    }

    private static String randomIos() {
        return ios(pick(IOS_APP_VERSIONS), pick(IOS_SYSTEM));
    }

    private static String android(final String versionName, final int sdk) {
        return "Signal-Android/" + versionName + " Android/" + sdk;
    }

    private static String ios(final String appVersion, final String systemVersion) {
        return "Signal-iOS/" + appVersion + " iOS/" + systemVersion;
    }

    private static Set<String> knownAgents() {
        final var agents = new HashSet<String>();
        for (final var version : ANDROID_VERSIONS) {
            for (final var sdk : ANDROID_SDK) {
                agents.add(android(version, sdk));
            }
        }
        for (final var appVersion : IOS_APP_VERSIONS) {
            for (final var systemVersion : IOS_SYSTEM) {
                agents.add(ios(appVersion, systemVersion));
            }
        }
        return Set.copyOf(agents);
    }

    private static String pick(final String[] values) {
        return values[RANDOM.nextInt(values.length)];
    }
}
