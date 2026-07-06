package com.picturejournal.shared.error;

/**
 * Foundation error taxonomy for application and domain failures.
 */
public enum ErrorCode {
    INVALID_ARGUMENT,
    RESOURCE_NOT_FOUND,
    CONFLICT,
    FORBIDDEN,
    UNAUTHORIZED,
    FOLDER_WRITE_NOT_ALLOWED,
    RATE_LIMITED,
    INTERNAL_ERROR
}
