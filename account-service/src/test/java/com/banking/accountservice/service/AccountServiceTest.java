package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.*;
import com.banking.accountservice.exception.*;
import com.banking.accountservice.repository.AccountRepository;
import com.banking.accountservice.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final String ACC_NO = "123456789012";
    private static final String TXN_ID = "txn01";
    private static final String EMAIL = "test@gmail.com";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @InjectMocks
    private AccountService accountService;

    // ---------- helpers ----------

    private static Account account(BigDecimal balance, AccountStatus status) {
        Account a = new Account();
        a.setEmail(EMAIL);
        a.setBalance(balance);
        a.setAccountStatus(status);
        return a;
    }

    private static LedgerEntry debit(String accountNumber, String amount) {
        LedgerEntry d = new LedgerEntry();
        d.setAccountNumber(accountNumber);
        d.setAmount(new BigDecimal(amount));
        return d;
    }

    private static CreateAccountRequest request(AccountType type) {
        CreateAccountRequest r = new CreateAccountRequest();
        r.setAccountHolderName("John Doe");
        r.setEmail(EMAIL);
        r.setPhone("9999999999");
        r.setAccountType(type);
        r.setInitialDeposit(new BigDecimal("5000"));
        return r;
    }

    /** BigDecimal.equals is scale-sensitive (1000 != 1000.00), so compare numerically. */
    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    private LedgerEntry captureSavedLedgerEntry() {
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());
        return captor.getValue();
    }

    private void assertLedgerEntry(LedgerEntry e, String amount, EntryType type) {
        assertEquals(TXN_ID, e.getTransactionId());
        assertEquals(AccountServiceTest.ACC_NO, e.getAccountNumber());
        assertAmount(amount, e.getAmount());
        assertEquals(type, e.getEntryType());
    }

    // ---------- createAccount ----------

    @Test
    void createAccount_savings_setsFieldsAndSavingsLimit() {
        CreateAccountRequest request = request(AccountType.SAVINGS);
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.createAccount(request);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account saved = captor.getValue();

        assertEquals("John Doe", saved.getAccountHolderName());
        assertEquals(EMAIL, saved.getEmail());
        assertEquals("9999999999", saved.getPhone());
        assertEquals(AccountType.SAVINGS, saved.getAccountType());
        assertEquals(AccountStatus.ACTIVE, saved.getAccountStatus());
        assertAmount("5000", saved.getBalance());
        assertAmount("100000", saved.getDailyTransactionLimit());
        assertTrue(saved.getAccountNumber().matches("\\d{12}"));

        assertEquals(saved.getAccountNumber(), response.getAccountNumber());
        assertEquals(EMAIL, response.getEmail());
    }

    @Test
    void createAccount_nonSavings_usesHigherDailyLimit() {
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        accountService.createAccount(request(AccountType.CURRENT));

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertAmount("500000", captor.getValue().getDailyTransactionLimit());
    }

    @Test
    void createAccount_regeneratesAccountNumberOnCollision() {
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(true, false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        accountService.createAccount(request(AccountType.SAVINGS));

        verify(accountRepository, times(2)).existsByAccountNumber(anyString());
    }

    @Test
    void createAccount_throwsWhenEmailAlreadyExists() {
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThrows(AccountAlreadyExistsException.class,
                () -> accountService.createAccount(request(AccountType.CURRENT)));

        verify(accountRepository, never()).save(any());
    }

    // ---------- getAccount / getBalance ----------

    @Test
    void getAccount_returnsMappedResponse() {
        Account account = account(new BigDecimal("1111"), AccountStatus.ACTIVE);
        account.setAccountNumber(ACC_NO);
        account.setAccountHolderName("John Doe");
        account.setPhone("9999999999");
        account.setAccountType(AccountType.SAVINGS);
        account.setDailyTransactionLimit(new BigDecimal("100000"));
        when(accountRepository.findByAccountNumber(ACC_NO)).thenReturn(Optional.of(account));

        AccountResponse r = accountService.getAccount(ACC_NO);

        assertEquals(ACC_NO, r.getAccountNumber());
        assertEquals("John Doe", r.getAccountHolderName());
        assertEquals(EMAIL, r.getEmail());
        assertEquals("9999999999", r.getPhone());
        assertEquals(AccountType.SAVINGS, r.getAccountType());
        assertEquals(AccountStatus.ACTIVE, r.getAccountStatus());
        assertAmount("1111", r.getBalance());
        assertAmount("100000", r.getDailyTransactionLimit());
    }

    @Test
    void getAccount_throwsWhenNotFound() {
        when(accountRepository.findByAccountNumber(ACC_NO)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> accountService.getAccount(ACC_NO));
    }

    @Test
    void getBalance_returnsBalance() {
        when(accountRepository.findByAccountNumber(ACC_NO))
                .thenReturn(Optional.of(account(new BigDecimal("1111"), AccountStatus.ACTIVE)));

        assertAmount("1111", accountService.getBalance(ACC_NO));
    }

    @Test
    void getBalance_throwsWhenNotFound() {
        when(accountRepository.findByAccountNumber(ACC_NO)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> accountService.getBalance(ACC_NO));
    }

    // ---------- blockAccount ----------

    @Test
    void blockAccount_setsStatusBlocked() {
        Account account = account(BigDecimal.TEN, AccountStatus.ACTIVE);
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.of(account));

        accountService.blockAccount(ACC_NO);

        assertEquals(AccountStatus.BLOCKED, account.getAccountStatus());
    }

    @Test
    void blockAccount_throwsWhenAccountMissing() {
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> accountService.blockAccount(ACC_NO));
    }

    // ---------- deductBalance ----------

    @Test
    void deductBalance_deductsAndWritesDebitEntry() {
        Account account = account(new BigDecimal("1100"), AccountStatus.ACTIVE);
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.DEBIT)).thenReturn(false);
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.of(account));

        accountService.deductBalance(TXN_ID, ACC_NO, new BigDecimal("100"));

        assertAmount("1000", account.getBalance());
        assertLedgerEntry(captureSavedLedgerEntry(), "100", EntryType.DEBIT);
    }

    @Test
    void deductBalance_allowsDeductingEntireBalance() {
        Account account = account(new BigDecimal("100"), AccountStatus.ACTIVE);
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.DEBIT)).thenReturn(false);
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.of(account));

        accountService.deductBalance(TXN_ID, ACC_NO, new BigDecimal("100"));

        assertAmount("0", account.getBalance());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-100", "-0.01"})
    void deductBalance_throwsWhenAmountNotPositive(BigDecimal amount) {
        assertThrows(InvalidAmountException.class,
                () -> accountService.deductBalance(TXN_ID, ACC_NO, amount));

        verifyNoInteractions(accountRepository, ledgerEntryRepository);
    }

    @Test
    void deductBalance_isNoOpWhenDebitAlreadyProcessed() {
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.DEBIT)).thenReturn(true);

        accountService.deductBalance(TXN_ID, ACC_NO, new BigDecimal("100"));

        verifyNoInteractions(accountRepository);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void deductBalance_throwsWhenAccountNotFound() {
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.DEBIT)).thenReturn(false);
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class,
                () -> accountService.deductBalance(TXN_ID, ACC_NO, new BigDecimal("100")));

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void deductBalance_throwsWhenAccountNotActive() {
        Account account = account(new BigDecimal("1100"), AccountStatus.BLOCKED);
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.DEBIT)).thenReturn(false);
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.of(account));

        assertThrows(AccountInactiveException.class,
                () -> accountService.deductBalance(TXN_ID, ACC_NO, new BigDecimal("100")));

        assertAmount("1100", account.getBalance());
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void deductBalance_throwsWhenInsufficientFunds() {
        Account account = account(new BigDecimal("1100"), AccountStatus.ACTIVE);
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.DEBIT)).thenReturn(false);
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.of(account));

        assertThrows(InsufficientFundsException.class,
                () -> accountService.deductBalance(TXN_ID, ACC_NO, new BigDecimal("1200")));

        assertAmount("1100", account.getBalance());
        verify(ledgerEntryRepository, never()).save(any());
    }

    // ---------- creditBalance ----------

    /** Stubs the "healthy" state: debit exists, not yet credited, not refunded. */
    private void stubCreditableTransaction() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT))
                .thenReturn(Optional.of(debit("SENDER", "200")));
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.CREDIT)).thenReturn(false);
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.REFUND)).thenReturn(false);
    }

    @Test
    void creditBalance_creditsAndWritesCreditEntry() {
        stubCreditableTransaction();
        Account account = account(new BigDecimal("1100"), AccountStatus.ACTIVE);
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.of(account));

        accountService.creditBalance(TXN_ID, ACC_NO, new BigDecimal("200"));

        assertAmount("1300", account.getBalance());
        assertLedgerEntry(captureSavedLedgerEntry(), "200", EntryType.CREDIT);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-200"})
    void creditBalance_throwsWhenAmountNotPositive(BigDecimal amount) {
        assertThrows(InvalidAmountException.class,
                () -> accountService.creditBalance(TXN_ID, ACC_NO, amount));

        verifyNoInteractions(accountRepository, ledgerEntryRepository);
    }

    @Test
    void creditBalance_throwsWhenNoDebitFound() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT)).thenReturn(Optional.empty());

        assertThrows(InvalidTransactionStateException.class,
                () -> accountService.creditBalance(TXN_ID, ACC_NO, new BigDecimal("200")));

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void creditBalance_throwsWhenAmountDiffersFromDebit() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT))
                .thenReturn(Optional.of(debit("SENDER", "100")));

        assertThrows(InvalidAmountException.class,
                () -> accountService.creditBalance(TXN_ID, ACC_NO, new BigDecimal("200")));

        verifyNoInteractions(accountRepository);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void creditBalance_isNoOpWhenAlreadyCredited() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT))
                .thenReturn(Optional.of(debit("SENDER", "200")));
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.CREDIT)).thenReturn(true);

        accountService.creditBalance(TXN_ID, ACC_NO, new BigDecimal("200"));

        verifyNoInteractions(accountRepository);
        verify(ledgerEntryRepository, never()).save(any());
    }


    @Test
    void creditBalance_throwsWhenAlreadyRefunded() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT))
                .thenReturn(Optional.of(debit("SENDER", "200")));
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.CREDIT)).thenReturn(false);
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.REFUND)).thenReturn(true);

        assertThrows(InvalidTransactionStateException.class,
                () -> accountService.creditBalance(TXN_ID, ACC_NO, new BigDecimal("200")));

        verifyNoInteractions(accountRepository);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void creditBalance_throwsWhenAccountNotFound() {
        stubCreditableTransaction();
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class,
                () -> accountService.creditBalance(TXN_ID, ACC_NO, new BigDecimal("200")));

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void creditBalance_throwsWhenAccountNotActive() {
        stubCreditableTransaction();
        Account account = account(new BigDecimal("1100"), AccountStatus.BLOCKED);
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.of(account));

        assertThrows(AccountInactiveException.class,
                () -> accountService.creditBalance(TXN_ID, ACC_NO, new BigDecimal("200")));

        assertAmount("1100", account.getBalance());
        verify(ledgerEntryRepository, never()).save(any());
    }

    // ---------- refundBalance ----------

    @Test
    void refundBalance_refundsAndWritesRefundEntry() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT))
                .thenReturn(Optional.of(debit(ACC_NO, "200")));
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.REFUND)).thenReturn(false);
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.CREDIT)).thenReturn(false);
        Account account = account(new BigDecimal("1100"), AccountStatus.ACTIVE);
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.of(account));

        accountService.refundBalance(TXN_ID, ACC_NO, new BigDecimal("200"));

        assertAmount("1300", account.getBalance());
        assertLedgerEntry(captureSavedLedgerEntry(), "200", EntryType.REFUND);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-200"})
    void refundBalance_throwsWhenAmountNotPositive(BigDecimal amount) {
        assertThrows(InvalidAmountException.class,
                () -> accountService.refundBalance(TXN_ID, ACC_NO, amount));

        verifyNoInteractions(accountRepository, ledgerEntryRepository);
    }

    @Test
    void refundBalance_throwsWhenNoDebitFound() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT)).thenReturn(Optional.empty());

        assertThrows(InvalidTransactionStateException.class,
                () -> accountService.refundBalance(TXN_ID, ACC_NO, new BigDecimal("200")));

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void refundBalance_throwsWhenAccountDiffersFromDebitedAccount() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT))
                .thenReturn(Optional.of(debit("12349876", "200")));

        assertThrows(InvalidAmountException.class,
                () -> accountService.refundBalance(TXN_ID, ACC_NO, new BigDecimal("200")));

        verifyNoInteractions(accountRepository);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void refundBalance_throwsWhenAmountDiffersFromDebit() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT))
                .thenReturn(Optional.of(debit(ACC_NO, "100")));

        assertThrows(InvalidAmountException.class,
                () -> accountService.refundBalance(TXN_ID, ACC_NO, new BigDecimal("200")));

        verifyNoInteractions(accountRepository);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void refundBalance_isNoOpWhenAlreadyRefunded() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT))
                .thenReturn(Optional.of(debit(ACC_NO, "200")));
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.REFUND)).thenReturn(true);

        accountService.refundBalance(TXN_ID, ACC_NO, new BigDecimal("200"));

        verifyNoInteractions(accountRepository);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void refundBalance_throwsWhenAlreadyCredited() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT))
                .thenReturn(Optional.of(debit(ACC_NO, "200")));
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.REFUND)).thenReturn(false);
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.CREDIT)).thenReturn(true);

        assertThrows(InvalidTransactionStateException.class,
                () -> accountService.refundBalance(TXN_ID, ACC_NO, new BigDecimal("200")));

        verifyNoInteractions(accountRepository);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void refundBalance_throwsWhenAccountNotFound() {
        when(ledgerEntryRepository.findForUpdate(TXN_ID, EntryType.DEBIT))
                .thenReturn(Optional.of(debit(ACC_NO, "200")));
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.REFUND)).thenReturn(false);
        when(ledgerEntryRepository.existsByTransactionIdAndEntryType(TXN_ID, EntryType.CREDIT)).thenReturn(false);
        when(accountRepository.findByAccountNumberForUpdate(ACC_NO)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class,
                () -> accountService.refundBalance(TXN_ID, ACC_NO, new BigDecimal("200")));

        verify(ledgerEntryRepository, never()).save(any());
    }

    // ---------- getCustomerEmailAndBalance / getAccountStatus ----------

    @Test
    void getCustomerEmailAndBalance_returnsEmailAndBalance() {
        when(accountRepository.findByAccountNumber(ACC_NO))
                .thenReturn(Optional.of(account(new BigDecimal("1100"), AccountStatus.ACTIVE)));

        Map<String, Object> result = accountService.getCustomerEmailAndBalance(ACC_NO);

        assertEquals(EMAIL, result.get("email"));
        assertAmount("1100", (BigDecimal) result.get("balance"));
    }

    @Test
    void getCustomerEmailAndBalance_throwsWhenAccountNotFound() {
        when(accountRepository.findByAccountNumber(ACC_NO)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class,
                () -> accountService.getCustomerEmailAndBalance(ACC_NO));
    }

    @Test
    void getAccountStatus_returnsStatusAsString() {
        when(accountRepository.findByAccountNumber(ACC_NO))
                .thenReturn(Optional.of(account(BigDecimal.ZERO, AccountStatus.BLOCKED)));

        assertEquals("BLOCKED", accountService.getAccountStatus(ACC_NO));
    }

    @Test
    void getAccountStatus_throwsWhenAccountNotFound() {
        when(accountRepository.findByAccountNumber(ACC_NO)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> accountService.getAccountStatus(ACC_NO));
    }
}