package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.exception.AccountNotFoundException;
import com.banking.accountservice.service.AccountService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountService accountService;


    private static final String ID = "01234";
    private static final String ACC_NO = "123456789012";
    private static final String ACC_HOLDER_NAME = "Abhi";
    private static final String EMAIL = "test@gmail.com";
    private static final String PHONE = "1234567890";


    @Test
    void createAccount_validRequest_returns201() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(
                ACC_HOLDER_NAME,
                EMAIL,
                PHONE,
                AccountType.SAVINGS,
                new BigDecimal("1000.00"));
        AccountResponse response = new AccountResponse(
                ID,
                ACC_NO,
                ACC_HOLDER_NAME,
                EMAIL,
                PHONE,
                AccountType.SAVINGS,
                AccountStatus.ACTIVE,
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                LocalDateTime.now()
        );


        when(accountService.createAccount(any(CreateAccountRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountHolderName")
                        .value(ACC_HOLDER_NAME))
                .andExpect(jsonPath("$.email")
                        .value(EMAIL))
                .andExpect(jsonPath("$.phone")
                        .value(PHONE))
                .andExpect(jsonPath("$.accountType")
                        .value("SAVINGS"))
                .andExpect(jsonPath("$.balance")
                        .value(1000.00));

        verify(accountService, times(1)).createAccount(any(CreateAccountRequest.class));

    }


    @Test
    void createAccount_invalidEmail_returns400() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(
                ACC_HOLDER_NAME, "not-an-email", PHONE,
                AccountType.SAVINGS, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountService);
    }

    @Test
    void createAccount_blankName_returns400() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(
                "", EMAIL, PHONE,
                AccountType.SAVINGS, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountService);
    }


    @Test
    void getAccount_existingAccount_returns201() throws Exception {
        AccountResponse response = new AccountResponse(
                ID,
                ACC_NO,
                ACC_HOLDER_NAME,
                EMAIL,
                PHONE,
                AccountType.SAVINGS,
                AccountStatus.ACTIVE,
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                LocalDateTime.now()
        );

        when(accountService.getAccount(ACC_NO)).thenReturn(response);

        mockMvc.perform(get("/api/v1/accounts/{accountNumber}", ACC_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(ID))
                .andExpect(jsonPath("$.accountNumber")
                        .value(ACC_NO))
                .andExpect(jsonPath("$.accountHolderName")
                        .value(ACC_HOLDER_NAME));


        verify(accountService, times(1)).getAccount("123456789012");


    }

    @Test
    void getAccount_notFound_returns404() throws Exception {
        when(accountService.getAccount(ACC_NO))
                .thenThrow(new AccountNotFoundException("Account not found"));

        mockMvc.perform(get("/api/v1/accounts/{accountNumber}", ACC_NO))
                .andExpect(status().isNotFound());

        verify(accountService).getAccount(ACC_NO);
    }

    @Test
    void getCustomerEmailAndBalance_existingAccount_returns200() throws Exception {
        Map<String, Object> result = Map.of(
                "email", EMAIL,
                "balance", new BigDecimal("1000.00"));

        when(accountService.getCustomerEmailAndBalance(ACC_NO)).thenReturn(result);

        mockMvc.perform(get("/api/v1/accounts/{accountNumber}/balance-email", ACC_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@gmail.com"))
                .andExpect(jsonPath("$.balance").value(1000.00));

        verify(accountService, times(1)).getCustomerEmailAndBalance(ACC_NO);
    }

}