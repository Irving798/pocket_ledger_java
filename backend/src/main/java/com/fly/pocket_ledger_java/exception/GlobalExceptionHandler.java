package com.fly.pocket_ledger_java.exception;

import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.vo.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局接口异常处理器，将参数、业务、协议及未知异常转换为统一响应结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 契约约定的 422 文案模板："参数错误：字段 原因"，DTO 里的 message 只写"原因"部分 */
    private static final String PARAM_ERROR_TEMPLATE = "参数错误：%s %s";

    /**
     * 处理查询参数或表单参数绑定、校验失败，并按契约返回 422。
     *
     * @param exception 参数绑定异常
     * @return 统一参数错误响应
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleBindException(BindException exception) {
        return paramFailure(exception);
    }

    /**
     * @Valid @RequestBody 参数校验失败。Spring Framework 5.2 中它不是 BindException 子类，必须单独处理。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception) {
        return paramFailure(exception.getBindingResult());
    }

    /**
     * 业务异常：状态码由异常本身决定（ResponseEntity 用于运行时决定状态码，@ResponseStatus 只适合恒定状态码）。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        int status = exception.getCode() >= 400 && exception.getCode() <= 599
                ? exception.getCode()
                : HttpStatus.BAD_REQUEST.value();
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(exception.getCode(), exception.getMessage()));
    }

    /**
     * 请求体不是合法 JSON：契约错误表把它归入 422 参数校验语义（对齐 FastAPI 行为）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ApiResponse.failure(ResultCode.PARAM_INVALID.getCode(), "参数错误：请求体不是合法 JSON");
    }

    /**
     * Content-Type 不受支持：契约通用错误表没有 415，统一按 422 语义返回。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ApiResponse.failure(ResultCode.PARAM_INVALID.getCode(),
                "参数错误：Content-Type 不受支持，请使用 application/json");
    }

    /**
     * 路径存在但请求方法不对：契约约定 405（否则会被下方 Exception 兜底成 500）。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return ApiResponse.failure(ResultCode.METHOD_NOT_ALLOWED);
    }

    /**
     * 记录未被其他处理器覆盖的异常，并隐藏内部细节后返回 500。
     *
     * @param exception 未知异常
     * @return 统一服务器内部错误响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpectedException(Exception exception) {
        LOGGER.error("Unhandled request exception", exception);
        return ApiResponse.failure(ResultCode.INTERNAL_ERROR);
    }



    //私有辅助方法
    /**
     * 从校验结果提取首个字段错误，拼装契约要求的参数错误文案。
     *
     * @param bindingResult Spring 参数绑定与校验结果
     * @return 统一参数错误响应
     */
    private ApiResponse<Void> paramFailure(BindingResult bindingResult) {
        FieldError fieldError = bindingResult.getFieldError();
        // 没有可用字段错误时退回通用参数错误，避免组装空字段名或空原因。
        if (fieldError == null) {
            return ApiResponse.failure(ResultCode.PARAM_INVALID);
        }
        // 只暴露字段名与 DTO 中定义的校验原因，不向客户端泄露框架内部信息。
        String message = String.format(PARAM_ERROR_TEMPLATE,
                fieldError.getField(), fieldError.getDefaultMessage());
        return ApiResponse.failure(ResultCode.PARAM_INVALID.getCode(), message);
    }
}
