package com.fly.pocket_ledger_java.controller;

import com.fly.pocket_ledger_java.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查：GET / 与业务接口一致，返回统一 ApiResponse 包装。
 */
@RestController
public class HealthController {

    /**
     * 返回无需鉴权的服务健康信息。
     *
     * @return 统一格式的健康检查响应
     */
    @GetMapping("/")
    public ApiResponse<Void> health() {
        return ApiResponse.success("Hello World", null);
    }
}
