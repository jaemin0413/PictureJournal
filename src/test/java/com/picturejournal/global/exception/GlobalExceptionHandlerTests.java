package com.picturejournal.global.exception;

import com.picturejournal.global.dto.response.ErrorResponse;
import com.picturejournal.global.error.ErrorCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ValidationTestController())
            .setControllerAdvice(handler)
            .build();

    @Test
    void domainExceptionMapsFolderWriteDeniedToForbidden() {
        DomainException exception = new DomainException(
                ErrorCode.FOLDER_WRITE_NOT_ALLOWED,
                "Denied by policy");

        ResponseEntity<ErrorResponse> response = handler.handleDomainException(exception);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(ErrorCode.FOLDER_WRITE_NOT_ALLOWED.name(), response.getBody().code());
        assertEquals("Denied by policy", response.getBody().message());
    }

    @Test
    void internalDomainExceptionUsesGenericClientMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDomainException(new DomainException(ErrorCode.INTERNAL_ERROR, "boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(ErrorCode.INTERNAL_ERROR.name(), response.getBody().code());
        assertEquals("An unexpected error occurred.", response.getBody().message());
    }

    @Test
    void beanValidationMapsToFoundationErrorEnvelope() throws Exception {
        mockMvc.perform(post("/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_ARGUMENT.name()))
                .andExpect(jsonPath("$.message").value("Validation failed."))
                .andExpect(jsonPath("$.details.name").value("must not be blank"));
    }

    @Test
    void genericExceptionMapsToInternalError() {
        ResponseEntity<ErrorResponse> response = handler.handleException(new IllegalStateException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(ErrorCode.INTERNAL_ERROR.name(), response.getBody().code());
        assertEquals("An unexpected error occurred.", response.getBody().message());
    }

    @RestController
    static class ValidationTestController {

        @PostMapping("/validation")
        void validate(@Valid @RequestBody ValidationPayload payload) {
        }
    }

    record ValidationPayload(@NotBlank String name) {
    }
}
