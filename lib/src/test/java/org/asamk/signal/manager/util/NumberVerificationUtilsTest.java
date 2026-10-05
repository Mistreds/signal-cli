package org.asamk.signal.manager.util;

import org.junit.jupiter.api.Test;
import org.whispersystems.signalservice.api.push.exceptions.RequestVerificationCodeRateLimitException;
import org.whispersystems.signalservice.internal.push.RegistrationSessionMetadataJson;
import org.whispersystems.signalservice.internal.push.RegistrationSessionMetadataResponse;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NumberVerificationUtilsTest {

    @Test
    void usesRetryAfterHeaderBeforeSessionDelay() {
        final var session = session(120, null, 3_600_000L);
        final var exception = new RequestVerificationCodeRateLimitException(session);

        assertEquals(3_600_000L, NumberVerificationUtils.registrationRetryAfterMilliseconds(exception, false));
    }

    @Test
    void fallsBackToNextSmsWhenRetryAfterHeaderIsMissing() {
        final var session = session(120, 30, null);
        final var exception = new RequestVerificationCodeRateLimitException(session);

        assertEquals(120_000L, NumberVerificationUtils.registrationRetryAfterMilliseconds(exception, false));
    }

    @Test
    void voiceVerificationUsesNextCall() {
        final var session = session(120, 30, null);
        final var exception = new RequestVerificationCodeRateLimitException(session);

        assertEquals(30_000L, NumberVerificationUtils.registrationRetryAfterMilliseconds(exception, true));
    }

    @Test
    void readsRetryAfterFromGenericRateLimitException() {
        final var exception = new org.whispersystems.signalservice.api.push.exceptions.RateLimitException(429,
                "Rate limit exceeded: 429",
                Optional.of(5_000L));

        assertEquals(5_000L, NumberVerificationUtils.registrationRetryAfterMilliseconds(exception, false));
    }

    @Test
    void returnsNullWhenServerOmitsRetryAfter() {
        final var session = session(null, null, null);
        final var exception = new RequestVerificationCodeRateLimitException(session);

        assertNull(NumberVerificationUtils.registrationRetryAfterMilliseconds(exception, false));
    }

    private static RegistrationSessionMetadataResponse session(
            final Integer nextSms,
            final Integer nextCall,
            final Long retryAfterMilliseconds
    ) {
        final var metadata = new RegistrationSessionMetadataJson("session",
                nextSms,
                nextCall,
                null,
                false,
                List.of(),
                false);
        return new RegistrationSessionMetadataResponse(metadata, System.currentTimeMillis(), retryAfterMilliseconds);
    }
}
