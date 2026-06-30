package org.asamk.signal.manager;

import org.asamk.signal.manager.api.AccountCheckException;
import org.asamk.signal.manager.api.NotRegisteredException;
import org.asamk.signal.manager.api.Pair;
import org.asamk.signal.manager.api.ServiceEnvironment;
import org.asamk.signal.manager.config.ServiceConfig;
import org.asamk.signal.manager.config.ServiceEnvironmentConfig;
import org.asamk.signal.manager.internal.AccountFileUpdaterImpl;
import org.asamk.signal.manager.internal.ManagerImpl;
import org.asamk.signal.manager.internal.MultiAccountManagerImpl;
import org.asamk.signal.manager.internal.PathConfig;
import org.asamk.signal.manager.internal.ProvisioningManagerImpl;
import org.asamk.signal.manager.internal.RegistrationManagerImpl;
import org.asamk.signal.manager.storage.SignalAccount;
import org.asamk.signal.manager.storage.accounts.AccountsStorage;
import org.asamk.signal.manager.storage.accounts.AccountsStore;
import org.asamk.signal.manager.util.KeyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;
import org.whispersystems.signalservice.api.push.exceptions.DeprecatedVersionException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public class SignalAccountFiles {

    private static final Logger logger = LoggerFactory.getLogger(MultiAccountManager.class);
    private static final int MAX_ACCOUNT_CHECK_ATTEMPTS = 10;
    private static final long ACCOUNT_CHECK_RETRY_DELAY_MS = 10_000;
    private static final int PROGRESS_BAR_WIDTH = 30;
    private static final ThreadLocal<Boolean> LOAD_PROGRESS_ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> LOAD_PROGRESS_LINE_VISIBLE = ThreadLocal.withInitial(() -> false);

    private final PathConfig pathConfig;
    private final ServiceEnvironment serviceEnvironment;
    private final ServiceEnvironmentConfig serviceEnvironmentConfig;
    private final String userAgent;
    private final Settings settings;
    private final AccountsStore accountsStore;

    public SignalAccountFiles(
            final File settingsPath,
            final ServiceEnvironment serviceEnvironment,
            final String userAgent,
            final Settings settings
    ) throws IOException {
        this.pathConfig = PathConfig.createDefault(settingsPath);
        this.serviceEnvironment = serviceEnvironment;
        this.serviceEnvironmentConfig = ServiceConfig.getServiceEnvironmentConfig(this.serviceEnvironment, userAgent);
        this.userAgent = userAgent;
        this.settings = settings;
        this.accountsStore = new AccountsStore(pathConfig.dataPath(), serviceEnvironment, accountPath -> {
            if (accountPath == null || !SignalAccount.accountFileExists(pathConfig.dataPath(), accountPath)) {
                return null;
            }

            try {
                return SignalAccount.load(pathConfig.dataPath(), accountPath, false, settings);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public Set<String> getAllLocalAccountNumbers() throws IOException {
        return accountsStore.getAllNumbers();
    }

    public MultiAccountManager initMultiAccountManager() throws IOException {
        final var accounts = accountsStore.getAllAccounts()
                .stream()
                .sorted(Comparator.comparing(AccountsStorage.Account::number))
                .toList();
        final List<Pair<Manager, Throwable>> managerPairs;
        if (settings.ignoreUnregisteredAccounts()) {
            managerPairs = loadAccountsWithProgress(accounts);
        } else {
            managerPairs = loadAccountsParallel(accounts);
        }

        for (final var pair : managerPairs) {
            if (pair.second() instanceof IOException e) {
                throw e;
            }
        }

        final var managers = managerPairs.stream()
                .filter(p -> p != null && p.first() != null)
                .map(Pair::first)
                .toList();
        return new MultiAccountManagerImpl(managers, this);
    }

    private List<Pair<Manager, Throwable>> loadAccountsParallel(
            final List<AccountsStorage.Account> accounts
    ) {
        return accounts.parallelStream().map(a -> {
            try {
                return new Pair<Manager, Throwable>(initManager(a.number(), a.path()), null);
            } catch (NotRegisteredException e) {
                logger.warn("Ignoring {}: {} ({})", a.number(), e.getMessage(), e.getClass().getSimpleName());
                return null;
            } catch (AccountCheckException | IOException e) {
                logger.error("Failed to load {}: {} ({})", a.number(), e.getMessage(), e.getClass().getSimpleName());
                return new Pair<Manager, Throwable>(null, e);
            }
        }).filter(Objects::nonNull).toList();
    }

    private List<Pair<Manager, Throwable>> loadAccountsWithProgress(
            final List<AccountsStorage.Account> accounts
    ) {
        if (accounts.isEmpty()) {
            return List.of();
        }

        final var totalAccounts = accounts.size();
        LOAD_PROGRESS_ACTIVE.set(true);
        var loadedCount = 0;
        var lastLoggedPercent = -1;
        try {
            final var managerPairs = new ArrayList<Pair<Manager, Throwable>>();
            for (var processed = 1; processed <= totalAccounts; processed++) {
                final var account = accounts.get(processed - 1);
                try {
                    managerPairs.add(new Pair<>(initManager(account.number(), account.path()), null));
                    loadedCount++;
                } catch (NotRegisteredException ignored) {
                } catch (AccountCheckException | IOException e) {
                    logErrorDuringAccountLoad(account.number(), e.getMessage(), e.getClass().getSimpleName());
                    managerPairs.add(new Pair<>(null, e));
                }

                lastLoggedPercent = reportLoadProgress(processed, totalAccounts, loadedCount, lastLoggedPercent);
            }

            return managerPairs;
        } finally {
            finishLoadProgressLine();
            LOAD_PROGRESS_ACTIVE.set(false);
            logger.info("Loaded {} working accounts out of {}", loadedCount, totalAccounts);
        }
    }

    private static void logErrorDuringAccountLoad(
            final String number,
            final String message,
            final String exceptionClass
    ) {
        clearLoadProgressLine();
        logger.error("Failed to load {}: {} ({})", number, message, exceptionClass);
    }

    private static void logRetryDuringAccountLoad(
            final String number,
            final int attempt,
            final String message
    ) {
        clearLoadProgressLine();
        logger.warn("Failed to check account {} (attempt {}/{}): {}, retrying in {} seconds",
                number,
                attempt,
                MAX_ACCOUNT_CHECK_ATTEMPTS,
                message,
                ACCOUNT_CHECK_RETRY_DELAY_MS / 1000);
    }

    private static void sleepBeforeAccountCheckRetry(final String number, final int attempt) {
        try {
            Thread.sleep(ACCOUNT_CHECK_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting to retry account check for {} (attempt {}/{})",
                    number,
                    attempt,
                    MAX_ACCOUNT_CHECK_ATTEMPTS);
        }
    }

    private static int reportLoadProgress(
            final int processed,
            final int total,
            final int loadedCount,
            final int lastLoggedPercent
    ) {
        if (!Boolean.TRUE.equals(LOAD_PROGRESS_ACTIVE.get())) {
            return lastLoggedPercent;
        }
        final var percent = processed * 100 / total;
        final var message = String.format("Loading accounts: %s %d/%d checked, %d working",
                formatProgressBar(percent),
                processed,
                total,
                loadedCount);
        if (useInPlaceProgress()) {
            System.err.print("\033[2K\r" + message);
            System.err.flush();
            LOAD_PROGRESS_LINE_VISIBLE.set(true);
            return lastLoggedPercent;
        }
        if (percent != lastLoggedPercent || processed == total) {
            logger.info(message);
            return percent;
        }
        return lastLoggedPercent;
    }

    private static boolean useInPlaceProgress() {
        if (Boolean.parseBoolean(System.getenv().getOrDefault("SIGNAL_CLI_PLAIN_PROGRESS", "false"))) {
            return false;
        }
        return System.console() != null;
    }
    private static void clearLoadProgressLine() {
        if (!Boolean.TRUE.equals(LOAD_PROGRESS_LINE_VISIBLE.get())) {
            return;
        }
        System.err.println();
        LOAD_PROGRESS_LINE_VISIBLE.set(false);
    }

    private static void finishLoadProgressLine() {
        clearLoadProgressLine();
    }

    private static String formatProgressBar(final int percent) {
        final var filled = Math.min(PROGRESS_BAR_WIDTH, percent * PROGRESS_BAR_WIDTH / 100);
        return "[" + "=".repeat(filled) + " ".repeat(PROGRESS_BAR_WIDTH - filled) + "]";
    }

    public Manager initManager(String number) throws IOException, NotRegisteredException, AccountCheckException {
        final var accountPath = accountsStore.getPathByNumber(number);
        return this.initManager(number, accountPath);
    }

    private Manager initManager(
            String number,
            String accountPath
    ) throws IOException, NotRegisteredException, AccountCheckException {
        if (accountPath == null) {
            throw new NotRegisteredException();
        }
        if (!SignalAccount.accountFileExists(pathConfig.dataPath(), accountPath)) {
            throw new NotRegisteredException();
        }

        for (var attempt = 1; attempt <= MAX_ACCOUNT_CHECK_ATTEMPTS; attempt++) {
            var account = SignalAccount.load(pathConfig.dataPath(), accountPath, true, settings);
            if (!number.equals(account.getNumber())) {
                account.close();
                throw new IOException("Number in account file doesn't match expected number: " + account.getNumber());
            }

            if (!account.isRegistered()) {
                account.close();
                throw new NotRegisteredException();
            }

            if (account.getServiceEnvironment() != null && account.getServiceEnvironment() != serviceEnvironment) {
                account.close();
                throw new IOException("Account is registered in another environment: " + account.getServiceEnvironment());
            }

            account.initDatabase();

            final var manager = new ManagerImpl(account,
                    pathConfig,
                    new AccountFileUpdaterImpl(accountsStore, accountPath),
                    serviceEnvironmentConfig,
                    userAgent);

            try {
                manager.checkAccountState();
            } catch (DeprecatedVersionException e) {
                manager.close();
                throw new IOException("signal-cli version is too old for the Signal-Server, please update.");
            } catch (IOException e) {
                manager.close();
                if (isAuthorizationFailed(e)) {
                    throw new AccountCheckException("Error while checking account " + number + ": " + e.getMessage(), e);
                }
                if (attempt < MAX_ACCOUNT_CHECK_ATTEMPTS) {
                    logRetryDuringAccountLoad(number, attempt, e.getMessage());
                    sleepBeforeAccountCheckRetry(number, attempt);
                    continue;
                }
                throw new AccountCheckException("Error while checking account " + number + ": " + e.getMessage(), e);
            }

            if (account.getServiceEnvironment() == null) {
                account.setServiceEnvironment(serviceEnvironment);
                accountsStore.updateAccount(accountPath, account.getNumber(), account.getAci());
            }

            return manager;
        }

        throw new AssertionError("Unreachable");
    }

    private static boolean isAuthorizationFailed(final IOException e) {
        if (e instanceof AuthorizationFailedException) {
            return true;
        }
        final var message = e.getMessage();
        return message != null && message.contains("Authorization failed");
    }

    public ProvisioningManager initProvisioningManager() {
        return initProvisioningManager(null);
    }

    public ProvisioningManager initProvisioningManager(Consumer<Manager> newManagerListener) {
        return new ProvisioningManagerImpl(pathConfig,
                serviceEnvironmentConfig,
                userAgent,
                newManagerListener,
                accountsStore);
    }

    public RegistrationManager initRegistrationManager(String number) throws IOException {
        return initRegistrationManager(number, null);
    }

    public RegistrationManager initRegistrationManager(
            String number,
            Consumer<Manager> newManagerListener
    ) throws IOException {
        final var accountPath = accountsStore.getPathByNumber(number);
        if (accountPath == null || !SignalAccount.accountFileExists(pathConfig.dataPath(), accountPath)) {
            final var newAccountPath = accountPath == null ? accountsStore.addAccount(number, null) : accountPath;
            var aciIdentityKey = KeyUtils.generateIdentityKeyPair();
            var pniIdentityKey = KeyUtils.generateIdentityKeyPair();

            var profileKey = KeyUtils.createProfileKey();
            var account = SignalAccount.create(pathConfig.dataPath(),
                    newAccountPath,
                    number,
                    serviceEnvironment,
                    aciIdentityKey,
                    pniIdentityKey,
                    profileKey,
                    settings);
            account.initDatabase();

            return new RegistrationManagerImpl(account,
                    pathConfig,
                    serviceEnvironmentConfig,
                    userAgent,
                    newManagerListener,
                    new AccountFileUpdaterImpl(accountsStore, newAccountPath));
        }

        var account = SignalAccount.load(pathConfig.dataPath(), accountPath, true, settings);
        if (!number.equals(account.getNumber())) {
            account.close();
            throw new IOException("Number in account file doesn't match expected number: " + account.getNumber());
        }
        account.initDatabase();

        return new RegistrationManagerImpl(account,
                pathConfig,
                serviceEnvironmentConfig,
                userAgent,
                newManagerListener,
                new AccountFileUpdaterImpl(accountsStore, accountPath));
    }
}
