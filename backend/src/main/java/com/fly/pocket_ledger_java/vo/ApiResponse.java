package com.fly.pocket_ledger_java.vo;

import com.fly.pocket_ledger_java.common.ResultCode;
import lombok.Getter;

/**
 * 统一响应体：契约约定 code = 200 表示成功，HTTP 状态码与 code 恒等，见 docs/pocket-ledger-api.html。
 * Java 8 没有 record，企业常规写法是 Lombok @Getter + 私有构造 + 静态工厂，保证不可变。
 */
@Getter
public class ApiResponse<T> {

    /** 业务状态码，与 HTTP 状态码恒等 */
    private final int code;

    /** 提示文案，成功或失败时都要给出可读信息 */
    private final String message;

    /** 业务数据；失败时固定为 null */
    private final T data;

    /**
     * 私有构造，外部只能通过静态工厂创建，保证对象不可变。
     */
    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }
    //标准成功
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 需要自定义成功文案的接口用（如注册的"注册成功"、删除的"成功删除 N 条"）。
     */
    //自定义成功文案
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), message, data);
    }
    //标准失败
    public static <T> ApiResponse<T> failure(ResultCode resultCode) {
        return new ApiResponse<>(resultCode.getCode(), resultCode.getMessage(), null);
    }
    //自定义失败文案
    public static <T> ApiResponse<T> failure(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
