package com.gameneko.aigame.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.gameneko.aigame.persistence.GamePersistenceException;
import com.gameneko.aigame.content.WorldContentException;
import com.gameneko.aigame.narrative.NarrativeProviderException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException exception) {
        return new ErrorResponse(exception.getMessage() == null ? "Invalid request" : exception.getMessage());
    }

    @ExceptionHandler(WorldContentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleWorldContent(WorldContentException exception) {
        return new ErrorResponse(exception.getMessage() == null ? "Invalid world content" : exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadableMessage() {
        return new ErrorResponse("Request body must be valid JSON");
    }

    @ExceptionHandler(GamePersistenceException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handlePersistenceError(GamePersistenceException exception) {
        return new ErrorResponse(exception.getMessage());
    }

    @ExceptionHandler(NarrativeProviderException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ErrorResponse handleNarrativeProvider(NarrativeProviderException exception) {
        log.error("Narrative provider failure", exception);
        return new ErrorResponse(exception.getMessage() == null ? "叙事模型调用失败" : exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpected(Exception exception) {
        log.error("Unhandled request error", exception);
        return new ErrorResponse("Internal server error");
    }

    public record ErrorResponse(String error) {
    }
}
