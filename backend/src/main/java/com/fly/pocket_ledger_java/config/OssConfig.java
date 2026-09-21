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

/**
 * OSS 客户端配置类：负责创建阿里云 OSS 客户端 Bean，并在启动时校验 OSS 配置的合法性。
 * <p>
 * 配置错误在应用启动阶段直接失败，避免拖到第一次上传头像时才暴露难以定位的问题。
 */
@Configuration
public class OssConfig {

    /**
     * 创建 OSS 客户端 Bean：校验配置 → 组装连接参数 → 构建客户端。
     * <p>
     * destroyMethod = "shutdown" 表示应用关闭时自动调用客户端的 shutdown 方法释放连接资源。
     *
     * @param endpoint      OSS 访问域名
     * @param region        OSS 地域
     * @param bucket        存储桶名称
     * @param publicBaseUrl 对象公共访问域名（用于拼接头像 URL）
     * @param prefix        头像对象 Key 前缀，必须保持 avatars/
     * @return 可用的 OSS 客户端实例
     * @throws Exception 配置不合法或客户端构建失败时抛出
     */
    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(
            @Value("${aliyun.oss.endpoint}") String endpoint,
            @Value("${aliyun.oss.region}") String region,
            @Value("${aliyun.oss.bucket}") String bucket,
            @Value("${aliyun.oss.public-base-url}") String publicBaseUrl,
            @Value("${aliyun.oss.avatar-prefix}") String prefix) throws Exception {
        requireHttps(endpoint);
        requireHttps(publicBaseUrl);
        // 地域与 Bucket 不能为空；头像前缀必须保持 avatars/，防止头像写进错误目录。
        if (region.trim().isEmpty() || bucket.trim().isEmpty()
                || !"avatars/".equals(prefix)) {
            throw new IllegalArgumentException("请配置 OSS 地域、Bucket，并保留 avatars/ 前缀");
        }

        // 组装客户端连接参数。
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        // 使用 V4 签名，满足阿里云新地域、新接口的签名要求。
        config.setSignatureVersion(SignVersion.V4);
        // 建立连接超时 2 秒，读数据超时 4 秒，从连接池获取连接超时 1 秒。
        config.setConnectionTimeout(2000);
        config.setSocketTimeout(4000);
        config.setConnectionRequestTimeout(1000);
        // 连接池上限 20，控制并发上传时的连接开销。
        config.setMaxConnections(20);
        config.setRequestTimeoutEnabled(true);
        config.setRequestTimeout(8000);
        // 关闭 SDK 自动重试，保证上传/删除只执行一次，避免重复操作的副作用。
        config.setMaxErrorRetry(0);

        // 按校验后的配置构建客户端；访问凭证从环境变量读取，避免写死在代码或配置里。
        return OSSClientBuilder.create()
                .endpoint(endpoint)
                .region(region)
                .credentialsProvider(CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider())
                .clientConfiguration(config)
                .build();
    }

    /**
     * 校验 OSS 地址必须是合法的 HTTPS 地址：有域名，且不带凭证、查询参数和片段。
     *
     * @param value 待校验的 OSS 地址
     */
    private void requireHttps(String value) {
        URI uri = URI.create(value);
        // 必须是 HTTPS 且有主机名；拒绝内嵌凭证（userInfo）、查询参数与片段，防止敏感信息泄露到 URL 中。
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("OSS 地址必须为不带凭证和查询参数的 HTTPS 地址");
        }
    }
}
