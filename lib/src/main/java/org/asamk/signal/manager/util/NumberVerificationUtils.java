package org.asamk.signal.manager.util;

import org.asamk.signal.manager.api.CaptchaRequiredException;
import org.asamk.signal.manager.api.IncorrectPinException;
import org.asamk.signal.manager.api.NonNormalizedPhoneNumberException;
import org.asamk.signal.manager.api.Pair;
import org.asamk.signal.manager.api.PinLockMissingException;
import org.asamk.signal.manager.api.PinLockedException;
import org.asamk.signal.manager.api.RateLimitException;
import org.asamk.signal.manager.api.VerificationMethodNotAvailableException;
import org.asamk.signal.manager.helper.PinHelper;
import org.signal.core.models.MasterKey;
import org.signal.network.NetworkResult;
import org.signal.network.exceptions.NonSuccessfulResponseCodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.signalservice.api.push.exceptions.ChallengeRequiredException;
import org.whispersystems.signalservice.api.push.exceptions.NoSuchSessionException;
import org.whispersystems.signalservice.api.push.exceptions.RequestVerificationCodeRateLimitException;
import org.whispersystems.signalservice.api.push.exceptions.SubmitVerificationCodeRateLimitException;
import org.whispersystems.signalservice.api.push.exceptions.TokenNotAcceptedException;
import org.whispersystems.signalservice.api.registration.RegistrationApi;
import org.whispersystems.signalservice.internal.push.LockedException;
import org.whispersystems.signalservice.internal.push.PushServiceSocket.VerificationCodeTransport;
import org.whispersystems.signalservice.internal.push.RegistrationSessionMetadataResponse;
import org.whispersystems.signalservice.internal.push.VerifyAccountResponse;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.Consumer;

public class NumberVerificationUtils {

    private static final Logger logger = LoggerFactory.getLogger(NumberVerificationUtils.class);

    public static String handleVerificationSession(
            RegistrationApi registrationApi,
            String sessionId,
            Consumer<String> sessionIdSaver,
            boolean voiceVerification,
            String captcha
    ) throws CaptchaRequiredException, IOException, RateLimitException, VerificationMethodNotAvailableException {
        RegistrationSessionMetadataResponse sessionResponse;
        try {
            sessionResponse = getValidSession(registrationApi, sessionId, voiceVerification);
        } catch (ChallengeRequiredException e) {
            if (captcha != null) {
                sessionResponse = submitCaptcha(registrationApi, sessionId, captcha, voiceVerification);
            } else {
                throw new CaptchaRequiredException("Captcha Required");
            }
        }

        sessionId = sessionResponse.getMetadata().getId();
        sessionIdSaver.accept(sessionId);

        if (sessionResponse.getMetadata().getVerified()) {
            return sessionId;
        }

        if (sessionResponse.getMetadata().getAllowedToRequestCode()) {
            return sessionId;
        }

        final var nextAttempt = voiceVerification
                ? sessionResponse.getMetadata().getNextCall()
                : sessionResponse.getMetadata().getNextSms();
        if (nextAttempt == null) {
            throw new VerificationMethodNotAvailableException();
        } else if (nextAttempt > 0) {
            throw new RateLimitException(nextAttempt * 1000L);
        }

        final var nextVerificationAttempt = sessionResponse.getMetadata().getNextVerificationAttempt();
        if (nextVerificationAttempt != null && nextVerificationAttempt > 0) {
            throw new CaptchaRequiredException(nextVerificationAttempt * 1000L);
        }

        if (sessionResponse.getMetadata().getRequestedInformation().contains("captcha")) {
            if (captcha != null) {
                sessionResponse = submitCaptcha(registrationApi, sessionId, captcha, voiceVerification);
            }
            if (!sessionResponse.getMetadata().getAllowedToRequestCode()) {
                throw new CaptchaRequiredException("Captcha Required");
            }
        }

        return sessionId;
    }

    public static void requestVerificationCode(
            RegistrationApi registrationApi,
            String sessionId,
            boolean voiceVerification
    ) throws IOException, CaptchaRequiredException, NonNormalizedPhoneNumberException, RateLimitException {
        final var locale = Utils.getDefaultLocale(Locale.US);
        final var response = registrationApi.requestSmsVerificationCode(sessionId,
                locale,
                false,
                voiceVerification ? VerificationCodeTransport.VOICE : VerificationCodeTransport.SMS);
        try {
            unwrapRegistrationResponse(response, voiceVerification);
        } catch (ChallengeRequiredException e) {
            throw new CaptchaRequiredException(e.getMessage(), e);
        } catch (org.whispersystems.signalservice.api.push.exceptions.NonNormalizedPhoneNumberException e) {
            throw new NonNormalizedPhoneNumberException("Phone number is not normalized ("
                    + e.getMessage()
                    + "). Expected normalized: "
                    + e.getNormalizedNumber(), e);
        }
    }

    public static Pair<VerifyAccountResponse, MasterKey> verifyNumber(
            String sessionId,
            String verificationCode,
            String pin,
            PinHelper pinHelper,
            Verifier verifier
    ) throws IOException, PinLockedException, IncorrectPinException, PinLockMissingException {
        verificationCode = verificationCode.replace("-", "");
        try {
            final var response = verifier.verify(sessionId, verificationCode, null);

            return new Pair<>(response, null);
        } catch (LockedException e) {
            if (pin == null) {
                throw new PinLockedException(e.getTimeRemaining());
            }

            final var registrationLockData = pinHelper.getRegistrationLockData(pin, e);
            if (registrationLockData == null) {
                throw new PinLockMissingException();
            }

            var registrationLock = registrationLockData.getMasterKey().deriveRegistrationLock();
            VerifyAccountResponse response;
            try {
                response = verifier.verify(sessionId, verificationCode, registrationLock);
            } catch (LockedException _e) {
                throw new AssertionError("KBS Pin appeared to matched but reg lock still failed!");
            }

            return new Pair<>(response, registrationLockData.getMasterKey());
        }
    }

    private static RegistrationSessionMetadataResponse validateSession(
            final RegistrationApi registrationApi,
            final String sessionId,
            final boolean voiceVerification
    ) throws IOException, RateLimitException {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new NoSuchSessionException();
        }
        return unwrapRegistrationResponse(registrationApi.getRegistrationSessionStatus(sessionId), voiceVerification);
    }

    private static RegistrationSessionMetadataResponse requestValidSession(
            final RegistrationApi registrationApi,
            final boolean voiceVerification
    ) throws IOException, RateLimitException {
        return unwrapRegistrationResponse(registrationApi.createRegistrationSession(null, "", ""), voiceVerification);
    }

    private static RegistrationSessionMetadataResponse getValidSession(
            final RegistrationApi registrationApi,
            final String sessionId,
            final boolean voiceVerification
    ) throws IOException, RateLimitException {
        try {
            return validateSession(registrationApi, sessionId, voiceVerification);
        } catch (NoSuchSessionException e) {
            logger.debug("No registration session, creating new one.");
            return requestValidSession(registrationApi, voiceVerification);
        }
    }

    private static RegistrationSessionMetadataResponse submitCaptcha(
            RegistrationApi registrationApi,
            String sessionId,
            String captcha,
            boolean voiceVerification
    ) throws IOException, CaptchaRequiredException, RateLimitException {
        captcha = captcha == null ? null : captcha.replace("signalcaptcha://", "");
        try {
            return unwrapRegistrationResponse(registrationApi.submitCaptchaToken(sessionId, captcha), voiceVerification);
        } catch (ChallengeRequiredException | TokenNotAcceptedException _e) {
            throw new CaptchaRequiredException("Captcha not accepted");
        } catch (NonSuccessfulResponseCodeException e) {
            if (e.code == 400) {
                throw new CaptchaRequiredException("Captcha has invalid format");
            }
            throw e;
        }
    }

    private static <T> T unwrapRegistrationResponse(
            final NetworkResult<T> result,
            final boolean voiceVerification
    ) throws IOException, RateLimitException {
        if (result instanceof NetworkResult.StatusCodeError<?> error && isRegistrationRateLimit(error.getCode())) {
            throw new RateLimitException(registrationRetryAfterMilliseconds(error.getException(), voiceVerification));
        }
        try {
            return Utils.handleResponseException(result);
        } catch (org.whispersystems.signalservice.api.push.exceptions.RateLimitException e) {
            throw RateLimitException.from(e);
        }
    }

    static Long registrationRetryAfterMilliseconds(
            final NonSuccessfulResponseCodeException exception,
            final boolean voiceVerification
    ) {
        final var sessionRetryAfter = switch (exception) {
            case RequestVerificationCodeRateLimitException rateLimit ->
                    retryAfterFromSession(rateLimit.getSessionMetadata(), voiceVerification);
            case SubmitVerificationCodeRateLimitException rateLimit ->
                    retryAfterFromSession(rateLimit.getSessionMetadata(), voiceVerification);
            default -> null;
        };
        if (sessionRetryAfter != null) {
            return sessionRetryAfter;
        }
        if (exception instanceof org.whispersystems.signalservice.api.push.exceptions.RateLimitException rateLimit) {
            return rateLimit.getRetryAfterMilliseconds().orElse(null);
        }
        return null;
    }

    private static Long retryAfterFromSession(
            final RegistrationSessionMetadataResponse session,
            final boolean voiceVerification
    ) {
        final var retryAfterMilliseconds = retryAfterHeaderMilliseconds(session);
        if (retryAfterMilliseconds != null) {
            return retryAfterMilliseconds;
        }
        final var nextAttempt = voiceVerification
                ? session.getMetadata().getNextCall()
                : session.getMetadata().getNextSms();
        if (nextAttempt != null && nextAttempt > 0) {
            return nextAttempt * 1000L;
        }
        return null;
    }

    /**
     * Retry-After is stored as a Kotlin {@code Duration}. Its getter is not a legal Java identifier,
     * so it is read through the value-class unbox methods.
     */
    private static Long retryAfterHeaderMilliseconds(final RegistrationSessionMetadataResponse session) {
        if (RetryAfterAccess.GETTER == null
                || RetryAfterAccess.UNBOX == null
                || RetryAfterAccess.TO_MILLIS == null) {
            return null;
        }
        try {
            final var duration = RetryAfterAccess.GETTER.invoke(session);
            if (duration == null) {
                return null;
            }
            final var raw = (long) RetryAfterAccess.UNBOX.invoke(duration);
            return (long) RetryAfterAccess.TO_MILLIS.invoke(null, raw);
        } catch (ReflectiveOperationException e) {
            logger.debug("Failed to read registration Retry-After", e);
            return null;
        }
    }

    private static final class RetryAfterAccess {

        private static final Method GETTER = findGetter();
        private static final Method UNBOX = findUnbox();
        private static final Method TO_MILLIS = findToMillis();

        private static Method findGetter() {
            for (final var method : RegistrationSessionMetadataResponse.class.getMethods()) {
                if (method.getName().startsWith("getRetryAfterTimestamp") && method.getParameterCount() == 0) {
                    return method;
                }
            }
            return null;
        }

        private static Method findUnbox() {
            try {
                return Class.forName("kotlin.time.Duration").getMethod("unbox-impl");
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        private static Method findToMillis() {
            try {
                return Class.forName("kotlin.time.Duration").getMethod("getInWholeMilliseconds-impl", long.class);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    }

    private static boolean isRegistrationRateLimit(final int statusCode) {
        return statusCode == 413 || statusCode == 429;
    }

    public interface Verifier {

        VerifyAccountResponse verify(
                String sessionId,
                String verificationCode,
                String registrationLock
        ) throws IOException;
    }
}
