package org.asamk.signal.manager.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalUserAgentTest {

    @Test
    void randomAgentLooksLikeSignalAndroidOrIos() {
        final var seen = new HashSet<String>();
        for (var i = 0; i < 40; i++) {
            final var userAgent = SignalUserAgent.random();
            seen.add(userAgent);
            assertFalse(userAgent.toLowerCase().contains("signal-cli"));
            assertTrue(SignalUserAgent.isOfficialDeviceUserAgent(userAgent), userAgent);
        }
        assertTrue(seen.size() > 1);
    }

    @Test
    void rejectsSignalCliAndBlankAgents() {
        assertFalse(SignalUserAgent.isOfficialDeviceUserAgent(null));
        assertFalse(SignalUserAgent.isOfficialDeviceUserAgent("  "));
        assertFalse(SignalUserAgent.isOfficialDeviceUserAgent("Signal-Android/8.35.1 signal-cli/0.13.18"));
        assertFalse(SignalUserAgent.isOfficialDeviceUserAgent("signal-cli"));
        assertTrue(SignalUserAgent.isOfficialDeviceUserAgent("Signal-Android/8.36.3 Android/36"));
        assertTrue(SignalUserAgent.isOfficialDeviceUserAgent("Signal-iOS/8.38.0.2011 iOS/26.1"));
        assertFalse(SignalUserAgent.isOfficialDeviceUserAgent("Signal-iOS/8.35.1.4 iOS/26.1"));
    }
}
