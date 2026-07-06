package com.picturejournal.shared.error;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred.";

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException exception) {
        HttpStatus status = resolveStatus(exception.getErrorCode());
        if (exception.getErrorCode() == ErrorCode.INTERNAL_ERROR) {
            log.error("Unhandled domain exception", exception);
            return buildErrorResponse(status, ErrorCode.INTERNAL_ERROR.name(), INTERNAL_ERROR_MESSAGE, Map.of());
        }

        return buildErrorResponse(status, exception.getErrorCode().name(), exception.getMessage(), Map.of());
    }

    @ExceptionHandler({BindException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponse> handleValidationException(Exception exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (exception instanceof BindException bindException) {
            bindException.getBindingResult().getFieldErrors().forEach(error -> details.put(error.getField(), error.getDefaultMessage()));
        } else if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            methodArgumentNotValidException.getBindingResult().getFieldErrors().forEach(error -> details.put(error.getField(), error.getDefaultMessage()));
        }
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT.name(), "Validation failed.", details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleRequestParsingException(Exception exception) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT.name(), "Request is invalid.", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception exception) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        log.error("Unhandled exception", exception);
        return buildErrorResponse(status, ErrorCode.INTERNAL_ERROR.name(), INTERNAL_ERROR_MESSAGE, Map.of());
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(
                        Instant.now(),
                        status.value(),
                        status.getReasonPhrase(),
                        code,
                        message,
                        details));
    }

    private HttpStatus resolveStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case FORBIDDEN, FOLDER_WRITE_NOT_ALLOWED -> HttpStatus.FORBIDDEN;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    public record ErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String code,
            String message,
            Map<String, Object> details) {
    }
}
