package org.asamk.signal.manager.storage;

import org.asamk.signal.manager.Settings;
import org.asamk.signal.manager.api.ServiceEnvironment;
import org.asamk.signal.manager.util.KeyUtils;
import org.asamk.signal.manager.util.SignalUserAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceUserAgentTest {

    @TempDir
    Path directory;

    @Test
    void newAccountKeepsItsOwnAgentAcrossReload() throws Exception {
        final String first;
        try (final var account = createAccount("one")) {
            first = account.getUserAgent();
            assertTrue(SignalUserAgent.isOfficialDeviceUserAgent(first));
            assertEquals(first, account.ensureUserAgent());
        }

        try (final var account = SignalAccount.load(directory.toFile(), "one", true, Settings.DEFAULT)) {
            assertEquals(first, account.getUserAgent());
            assertEquals(first, account.ensureUserAgent());
        }

        try (final var other = createAccount("two")) {
            assertTrue(SignalUserAgent.isOfficialDeviceUserAgent(other.getUserAgent()));
        }
    }

    @Test
    void signalCliAgentIsReplacedOnce() throws Exception {
        final String replaced;
        try (final var account = createAccount("legacy")) {
            account.setDeviceUserAgent("Signal-Android/8.35.1 signal-cli/0.13.18");
            replaced = account.ensureUserAgent();
            assertTrue(SignalUserAgent.isOfficialDeviceUserAgent(replaced));
            assertNotEquals("Signal-Android/8.35.1 signal-cli/0.13.18", replaced);
            assertFalse(replaced.toLowerCase().contains("signal-cli"));
            assertEquals(replaced, account.ensureUserAgent());
        }

        try (final var account = SignalAccount.load(directory.toFile(), "legacy", true, Settings.DEFAULT)) {
            assertEquals(replaced, account.ensureUserAgent());
        }
    }

    @Test
    void accountFileWithoutUserAgentStillLoads() throws Exception {
        final var mapper = Utils.createStorageObjectMapper();
        final var json = """
                {
                  "version": 11,
                  "timestamp": 1,
                  "serviceEnvironment": "STAGING",
                  "registered": false,
                  "deviceId": 1,
                  "isMultiDevice": false,
                  "password": "secret"
                }
                """;
        final var storage = mapper.readValue(json, SignalAccount.Storage.class);
        assertNull(storage.userAgent());
        assertEquals("STAGING", storage.serviceEnvironment());
    }

    private SignalAccount createAccount(final String name) throws Exception {
        return SignalAccount.create(directory.toFile(),
                name,
                "+10000000000",
                ServiceEnvironment.STAGING,
                KeyUtils.generateIdentityKeyPair(),
                KeyUtils.generateIdentityKeyPair(),
                KeyUtils.createProfileKey(),
                Settings.DEFAULT);
    }
}
