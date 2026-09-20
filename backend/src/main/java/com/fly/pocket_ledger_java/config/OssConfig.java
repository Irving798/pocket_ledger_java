package com.fly.pocket_ledger_java.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.comm.SignVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;

/** 管理一个共享 OSS 客户端，不在每次上传时新建连接池。 */
@Configuration
public class OssConfig {
    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(
            @Value("${aliyun.oss.endpoint}") String endpoint,
            @Value("${aliyun.oss.region}") String region,
            @Value("${aliyun.oss.bucket}") String bucket,
            @Value("${aliyun.oss.public-base-url}") String publicBaseUrl,
            @Value("${aliyun.oss.avatar-prefix}") String prefix) throws Exception {
        // 启动时发现配置错误，避免第一次上传才出现难定位的问题。
        requireHttps(endpoint);
        requireHttps(publicBaseUrl);
        if (region.trim().isEmpty() || bucket.trim().isEmpty()
                || !"avatars/".equals(prefix)) {
            throw new IllegalArgumentException("请配置 OSS 地域、Bucket，并保留 avatars/ 前缀");
        }
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        config.setConnectionTimeout(2000);
        config.setSocketTimeout(4000);
        config.setConnectionRequestTimeout(1000);
        config.setMaxConnections(20);
        // 单次外部调用设置超时，不自动重发整个业务操作。
        config.setRequestTimeoutEnabled(true);
        config.setRequestTimeout(8000);
        config.setMaxErrorRetry(0);
        return OSSClientBuilder.create()
                .endpoint(endpoint)
                .region(region)
                .credentialsProvider(CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider())
                .clientConfiguration(config)
                .build();
    }

    /** URL 配置不接受凭证、查询串或片段；头像 URL 稍后直接拼接。 */
    private void requireHttps(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("OSS 地址必须为不带凭证和查询参数的 HTTPS 地址");
        }
    }
}