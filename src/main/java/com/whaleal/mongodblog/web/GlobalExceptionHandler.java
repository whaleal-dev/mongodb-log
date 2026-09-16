package com.whaleal.mongodblog.web;

import com.whaleal.mongodblog.task.TaskActiveException;
import com.whaleal.mongodblog.task.TaskDeletionException;
import com.whaleal.mongodblog.parser.ftdc.FtdcFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> badRequest(Exception error) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", message(error)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", message(error)));
    }

    @ExceptionHandler(TaskNotReadyException.class)
    public ResponseEntity<ApiError> notReady(TaskNotReadyException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("TASK_NOT_READY", message(error)));
    }

    @ExceptionHandler(TaskActiveException.class)
    public ResponseEntity<ApiError> activeTask(TaskActiveException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("TASK_ACTIVE", message(error)));
    }

    @ExceptionHandler(TaskDeletionException.class)
    public ResponseEntity<ApiError> deletionFailed(TaskDeletionException error) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("TASK_DELETE_FAILED", message(error)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("UPLOAD_TOO_LARGE", "上传文件超过 12GB 限制"));
    }

    @ExceptionHandler(FtdcFormatException.class)
    public ResponseEntity<ApiError> invalidFtdc(FtdcFormatException error) {
        return ResponseEntity.unprocessableEntity()
                .body(new ApiError("FTDC_FORMAT_ERROR", message(error)));
    }

    private String message(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
