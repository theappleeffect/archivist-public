package com.archivist.api;

/**
 * Immutable response from an API call.
 *
 * @param statusCode HTTP status code (0 for connection errors)
 * @param body       response body or error message
 * @param success    true if statusCode is in the 200-299 range
 */
public record ApiResponse(int statusCode, String body, boolean success) {

    /**
     * Creates an ApiResponse, computing success from the status code.
     */
    public static ApiResponse of(int statusCode, String body) {
        return new ApiResponse(statusCode, body, statusCode >= 200 && statusCode < 300);
    }

    /**
     * Creates an error response (status 0) with the given message.
     */
    public static ApiResponse error(String message) {
        return new ApiResponse(0, message, false);
    }
}
