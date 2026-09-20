package com.fly.pocket_ledger_java;

import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/** 上下文测试用 mock 替换 SDK Bean，不创建云端资源。 */
@SpringBootTest(properties = {
        "aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
        "aliyun.oss.region=cn-hangzhou",
        "aliyun.oss.bucket=test-bucket",
        "aliyun.oss.public-base-url=https://avatar.example.com"
})
class PocketLedgerJavaApplicationTests {
    @MockBean
    private OSS ossClient;

    @Test
    void contextLoads() {
        // Bean 能装配即通过；不会调用 OSS。
    }
}