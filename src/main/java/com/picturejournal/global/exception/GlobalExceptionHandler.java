package com.picturejournal.global.exception;

import com.picturejournal.global.dto.response.ErrorResponse;
import com.picturejournal.global.error.ErrorCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 애플리케이션에서 발생한 예외를 일관된 HTTP 오류 응답으로 변환한다.
 *
 * <p>{@link DomainException}의 {@link com.picturejournal.global.error.ErrorCode}를 HTTP 상태로 매핑하고,
 * 요청 본문 검증 오류와 타입 변환 오류는 필드별 세부 정보로 정리한다.
 * 예상하지 못한 예외는 내부 구현 정보를 노출하지 않고 INTERNAL_ERROR 응답으로 감싼다.</p>
 *
 * <p>모든 오류는 timestamp, HTTP status, 안정적인 code, 사용자 메시지와 선택 details를 가진
 * {@link com.picturejournal.global.dto.response.ErrorResponse} 형태로 직렬화된다.</p>
 */
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
}
