package com.fly.pocket_ledger_java;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 OSS 连通性验收。故意不加 @MockBean，才可能暴露网络和权限问题。
 * 只做业务真正使用的 PutObject、匿名 GET、DeleteObject 三步，
 * 不调用 doesBucketExist 之类需要额外 RAM 权限的接口，避免误判。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_OSS_INTEGRATION_TESTS", matches = "true")
class OssConnectivityIntegrationTest {

    /** 单元 10.2 已核对过的真实 1×1 PNG，能被浏览器解码。 */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQaPgPAAIzAZDtHsO2AAAAAElFTkSuQmCC");

    @Autowired
    private OSS ossClient;

    @Value("${aliyun.oss.bucket}")
    private String bucket;

    @Value("${aliyun.oss.public-base-url}")
    private String publicBaseUrl;

    @Test
    void putThenAnonymousGetThenDelete() throws Exception {
        String key = "avatars/0/connectivity-"
                + UUID.randomUUID().toString().replace("-", "") + ".png";
        try {
            // ② + ③：凭证有效且允许写入 avatars/*
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(PNG.length);
            metadata.setContentType("image/png");
            ossClient.putObject(bucket, key, new ByteArrayInputStream(PNG), metadata);

            // ④：模拟浏览器，不带任何 Authorization 头
            String url = publicBaseUrl.replaceAll("/+$", "") + "/" + key;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            assertThat(conn.getResponseCode())
                    .as("匿名读应为 200；403 说明「阻止公共访问」未关或桶仍是私有：" + url)
                    .isEqualTo(200);
            assertThat(conn.getContentType()).contains("image/png");
            assertThat(readFully(conn.getInputStream())).isEqualTo(PNG);
        } finally {
            // 顺便验证 DeleteObject 权限，且不留下垃圾对象
            ossClient.deleteObject(bucket, key);
        }
    }

    private byte[] readFully(InputStream stream) throws Exception {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return Arrays.copyOf(out.toByteArray(), read == -1 ? out.size() : out.size());
        }
    }
}