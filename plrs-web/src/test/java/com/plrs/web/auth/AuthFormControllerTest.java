package com.plrs.web.auth;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.plrs.application.user.EmailAlreadyRegisteredException;
import com.plrs.application.user.RegisterUserUseCase;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.user.UserId;
import com.plrs.web.common.GlobalExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = AuthFormController.class,
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = GlobalExceptionHandler.class))
class AuthFormControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private RegisterUserUseCase registerUseCase;

    @Test
    void getRegisterRenders200WithFormModel() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attributeExists("form"));
    }

    @Test
    void getRegisterHtmlContainsEmailAndPasswordInputs() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("Register")));
    }

    @Test
    void postRegisterHappyPathRedirectsToLoginWithRegisteredFlag() throws Exception {
        when(registerUseCase.handle(any())).thenReturn(UserId.of(UUID.randomUUID()));

        mockMvc.perform(
                        post("/register")
                                .param("email", "kumar@example.com")
                                .param("password", "Password01"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    void postRegisterDuplicateEmailRerendersFormWithFieldError() throws Exception {
        when(registerUseCase.handle(any()))
                .thenThrow(new EmailAlreadyRegisteredException("dup@example.com"));

        mockMvc.perform(
                        post("/register")
                                .param("email", "dup@example.com")
                                .param("password", "Password01"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attributeHasFieldErrors("form", "email"));
    }

    @Test
    void postRegisterWithBlankFieldsRerendersFormWithValidationErrors() throws Exception {
        mockMvc.perform(post("/register").param("email", "").param("password", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attributeHasFieldErrors("form", "email", "password"));
    }

    @Test
    void postRegisterDomainValidationExceptionRerendersFormWithGlobalError() throws Exception {
        when(registerUseCase.handle(any()))
                .thenThrow(new DomainValidationException("Password must be at least 10 characters"));

        mockMvc.perform(
                        post("/register")
                                .param("email", "kumar@example.com")
                                .param("password", "weak1A"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attributeHasErrors("form"))
                .andExpect(
                        content()
                                .string(
                                        containsString("Password must be at least 10 characters")));
    }
}
