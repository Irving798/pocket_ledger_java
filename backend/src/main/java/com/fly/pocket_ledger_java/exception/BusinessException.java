package com.fly.pocket_ledger_java.exception;

import com.fly.pocket_ledger_java.common.ResultCode;

/**
 * 业务异常：携带 HTTP 状态码语义（400 参数/业务错误、401 认证失败、409 冲突等），
 * 由 GlobalExceptionHandler 统一转成 ApiResponse 返回。
 * 优先用 ResultCode 枚举构造；动态文案（如"密码错误"）再用 (code, message) 构造。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
