package com.fly.pocket_ledger_java;

import com.aliyun.oss.OSS;
import com.fly.pocket_ledger_java.dto.RegisterDTO;
import com.fly.pocket_ledger_java.dto.UserProfileUpdateDTO;
import com.fly.pocket_ledger_java.entity.User;
import com.fly.pocket_ledger_java.mapper.UserMapper;
import com.fly.pocket_ledger_java.service.AuthService;
import com.fly.pocket_ledger_java.util.LoginUser;
import com.fly.pocket_ledger_java.util.UserContext;
import com.fly.pocket_ledger_java.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL 验证，不用 mock 的响应推断数据已经入库。 */
@SpringBootTest(properties = {
        "aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
        "aliyun.oss.region=cn-hangzhou",
        "aliyun.oss.bucket=test-bucket",
        "aliyun.oss.public-base-url=https://avatar.example.com"
})
@EnabledIfEnvironmentVariable(named = "RUN_DB_INTEGRATION_TESTS", matches = "true")
@Transactional
class UserProfileIntegrationTest {
    @Autowired private AuthService service;
    @Autowired private UserMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private OSS ossClient;

    @BeforeEach
    void requireTestDatabase() {
        // 连接后只读验证实际数据库，防止环境变量指向真实业务库。
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_test");
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private UserVO registerUser() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("profile_" + UUID.randomUUID().toString().replace("-", ""));
        dto.setPassword("testPassword123");
        dto.setNickname("重复昵称");
        dto.setEmail("same@example.com");
        return service.register(dto);
    }

    @Test
    void profilePersistsClearsAndDoesNotTouchAvatarOrAnotherUser() {
        UserVO first = registerUser();
        UserVO second = registerUser();
        // 两个用户昵称邮箱完全相同仍可注册，证明没有错误唯一性约束。
        User stored = mapper.selectById(first.getId());
        assertThat(stored.getNickname()).isEqualTo("重复昵称");
        assertThat(stored.getEmail()).isEqualTo("same@example.com");
        assertThat(stored.getAvatarObjectKey()).isNull();
        String key = "avatars/" + first.getId() + "/00000000000000000000000000000000.png";
        jdbc.update("UPDATE fly_user SET avatar_object_key = ? WHERE id = ?", key, first.getId());
        UserContext.set(new LoginUser(first.getId(), first.getUsername()));
        UserProfileUpdateDTO dto = new UserProfileUpdateDTO();
        dto.setNickname("  ");
        dto.setEmail("");
        service.updateCurrentUser(dto);
        service.updateCurrentUser(dto); // 原样保存必须成功。
        User after = mapper.selectById(first.getId());
        assertThat(after.getNickname()).isNull();
        assertThat(after.getEmail()).isNull();
        assertThat(after.getAvatarObjectKey()).isEqualTo(key);
        assertThat(mapper.selectById(second.getId()).getEmail()).isEqualTo("same@example.com");
    }
}