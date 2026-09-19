package com.fly.pocket_ledger_java.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 健康检查：契约规定 GET / 返回裸 {"message":"Hello World"}，不走统一 ApiResponse 包装。
 */
@RestController
public class HealthController {

    /**
     * 返回无需鉴权、无需统一响应包装的服务健康信息。
     *
     * @return 固定的健康检查响应
     */
    @GetMapping("/")
    public Map<String, String> health() {
        // Java 8 没有 Map.of（Java 9+ 才有），用 Collections.singletonMap
        return Collections.singletonMap("message", "Hello World");
    }
}
