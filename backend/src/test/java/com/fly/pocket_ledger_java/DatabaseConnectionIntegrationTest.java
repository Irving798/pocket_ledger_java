package com.fly.pocket_ledger_java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import com.aliyun.oss.OSS;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
        "aliyun.oss.region=cn-hangzhou",
        "aliyun.oss.bucket=test-bucket",
        "aliyun.oss.public-base-url=https://avatar.example.com"
})
@EnabledIfEnvironmentVariable(named = "RUN_DB_INTEGRATION_TESTS", matches = "true")
class DatabaseConnectionIntegrationTest {

    @Autowired
    private DataSource dataSource;
    /** 数据库连通性验证不应依赖 OSS 凭证或网络。 */
    @MockBean
    private OSS ossClient;

    @Test
    void connectsToMysql() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(5)).isTrue();
            assertThat(connection.getCatalog()).isEqualTo("yihai_mdm_dev");
        }
    }
}
