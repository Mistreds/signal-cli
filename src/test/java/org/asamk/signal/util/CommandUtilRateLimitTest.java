package org.asamk.signal.util;

import org.asamk.signal.manager.api.RateLimitException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandUtilRateLimitTest {

    @Test
    void rateLimitMessageIncludesRetryAfterSeconds() {
        final var message = CommandUtil.getRateLimitMessage(new RateLimitException(3_600_000L));

        assertTrue(message.startsWith("Rate limit reached\nRetry after: 3600 seconds\nNext attempt may be tried at "));
    }

    @Test
    void rateLimitMessageRoundsPartialSecondsUp() {
        final var message = CommandUtil.getRateLimitMessage(new RateLimitException(1_001L));

        assertTrue(message.contains("Retry after: 2 seconds"));
    }

    @Test
    void rateLimitMessageWithoutDelayStaysShort() {
        assertEquals("Rate limit reached", CommandUtil.getRateLimitMessage(new RateLimitException(null)));
    }
}
