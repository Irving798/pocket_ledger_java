package com.fly.pocket_ledger_java.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.model.ObjectMetadata;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fly.pocket_ledger_java.entity.User;
import com.fly.pocket_ledger_java.exception.BusinessException;
import com.fly.pocket_ledger_java.mapper.UserMapper;
import com.fly.pocket_ledger_java.service.impl.AuthServiceImpl;
import com.fly.pocket_ledger_java.util.JwtUtils;
import com.fly.pocket_ledger_java.util.LoginUser;
import com.fly.pocket_ledger_java.util.UserContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.io.InputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通过公共业务方法验收私有校验逻辑，不用反射调用私有方法。 */
class AuthAvatarServiceTest {
    private final UserMapper mapper = mock(UserMapper.class);
    private final OSS oss = mock(OSS.class);
    private AuthServiceImpl service;
    private final String oldKey = "avatars/9/00000000000000000000000000000000.png";

    @BeforeEach
    void setUp() {
        // 纯 Mockito 不会启动 MyBatis，需要初始化 Lambda 列名元数据。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), User.class);
        service = new AuthServiceImpl(mapper, mock(PasswordEncoder.class), mock(JwtUtils.class), oss);
        ReflectionTestUtils.setField(service, "bucket", "test-bucket");
        ReflectionTestUtils.setField(service, "avatarPrefix", "avatars/");
        UserContext.set(new LoginUser(9L, "reed"));
        User user = new User();
        user.setId(9L);
        user.setAvatarObjectKey(oldKey);
        when(mapper.selectById(9L)).thenReturn(user);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.selectCount(any())).thenReturn(0L);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private MockMultipartFile png(int size) {
        // 这是魔数边界样本，不用于浏览器显示或证明完整 PNG 可解码。
        byte[] bytes = new byte[size];
        if (size >= 4) {
            bytes[0] = (byte) 0x89; bytes[1] = 0x50; bytes[2] = 0x4e; bytes[3] = 0x47;
        }
        return new MockMultipartFile("file", "untrusted.txt", "text/plain", bytes);
    }

    @Test
    void uploadThenUpdateThenDeleteOldObject() throws Exception {
        MockMultipartFile file = png(8);
        service.replaceAvatar(file);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InputStream> stream = ArgumentCaptor.forClass(InputStream.class);
        ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
        InOrder order = inOrder(oss, mapper);
        order.verify(oss).putObject(eq("test-bucket"), key.capture(), stream.capture(), metadata.capture());
        order.verify(mapper).update(isNull(), any());
        order.verify(oss).deleteObject("test-bucket", oldKey);
        assertThat(key.getValue()).matches("avatars/9/[0-9a-f]{32}\\.png");
        assertThat(metadata.getValue().getContentType()).isEqualTo("image/png");
        assertThat(metadata.getValue().getContentLength()).isEqualTo(8);
        assertThat(org.springframework.util.StreamUtils.copyToByteArray(stream.getValue())).isEqualTo(file.getBytes());
    }

    @Test
    void exactlyTwoMiBPassesButOneMoreByteFails() {
        service.replaceAvatar(png(2 * 1024 * 1024));
        clearInvocations(mapper, oss);
        assertThatThrownBy(() -> service.replaceAvatar(png(2 * 1024 * 1024 + 1)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(413));
        verifyNoInteractions(oss);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void renamedGifAndEmptyFileAreRejected() {
        MockMultipartFile fake = new MockMultipartFile("file", "fake.jpg", "image/jpeg", new byte[]{71, 73, 70, 56});
        assertThatThrownBy(() -> service.replaceAvatar(fake)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.replaceAvatar(png(0))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.replaceAvatar(null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(oss);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void jpegUsesDetectedMimeInsteadOfFileName() {
        service.replaceAvatar(new MockMultipartFile("file", "wrong.png", "image/png",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}));
        verify(oss).putObject(anyString(), endsWith(".jpg"), any(InputStream.class),
                argThat(metadata -> "image/jpeg".equals(metadata.getContentType())));
    }

    @Test
    void storageFailureDoesNotUpdateDatabase() {
        when(oss.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenThrow(new ClientException("simulated network failure"));
        assertThatThrownBy(() -> service.replaceAvatar(png(8)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
        verify(mapper, never()).update(any(), any());
        verify(oss, never()).deleteObject(anyString(), anyString());
    }

    @Test
    void databaseFailureDoesNotDeleteEitherObject() {
        when(mapper.update(isNull(), any())).thenThrow(new IllegalStateException("simulated database failure"));
        assertThatThrownBy(() -> service.replaceAvatar(png(8))).isInstanceOf(IllegalStateException.class);
        // 新 Key 会进入 AVATAR_LINK_FAILED 日志，由人工核对；不尝试网络补偿删除。
        verify(oss, never()).deleteObject(anyString(), anyString());
    }

    @Test
    void oldObjectDeletionFailureDoesNotFailReplacement() {
        doThrow(new ClientException("simulated delete failure")).when(oss).deleteObject("test-bucket", oldKey);
        assertThatCode(() -> service.replaceAvatar(png(8))).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void removalOnlySetsAvatarColumnToNull() {
        service.removeAvatar();
        ArgumentCaptor<LambdaUpdateWrapper<User>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("avatar_object_key").doesNotContain("nickname", "email");
        assertThat(captor.getValue().getParamNameValuePairs().values()).containsNull();
    }

    @Test
    void repeatedRemovalWithNoAvatarSucceeds() {
        User user = new User();
        user.setId(9L);
        when(mapper.selectById(9L)).thenReturn(user);
        when(mapper.update(isNull(), any())).thenReturn(0);
        service.removeAvatar();
        service.removeAvatar();
        verifyNoInteractions(oss);
    }

    @Test
    void networkMethodsDoNotStartTransactions() throws Exception {
        // 保护设计边界：禁止给整个类或两个网络编排方法加事务。
        assertThat(AuthServiceImpl.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(AuthServiceImpl.class.getMethod("replaceAvatar", org.springframework.web.multipart.MultipartFile.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(AuthServiceImpl.class.getMethod("removeAvatar").isAnnotationPresent(Transactional.class)).isFalse();
    }
}