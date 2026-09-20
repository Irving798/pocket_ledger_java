package com.fly.pocket_ledger_java.dto;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证两个入口的边界，不把“数据库能存下”当成“业务允许”。 */
class UserProfileValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    private RegisterDTO registration() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("reed");
        dto.setPassword("12345678");
        return dto;
    }

    private String repeat(String value, int count) {
        // Java 8 不支持 String.repeat，测试数据使用标准集合拼接。
        return String.join("", Collections.nCopies(count, value));
    }

    @Test
    void optionalFieldsCanBeMissingBlankOrPresentIndependently() {
        RegisterDTO register = registration();
        UserProfileUpdateDTO update = new UserProfileUpdateDTO();
        assertThat(VALIDATOR.validate(register)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
        register.setNickname("  "); register.setEmail("\t ");
        update.setNickname("  "); update.setEmail("\t ");
        assertThat(register.getNickname()).isNull();
        assertThat(register.getEmail()).isNull();
        assertThat(update.getNickname()).isNull();
        assertThat(update.getEmail()).isNull();
        register.setNickname("只有昵称"); update.setNickname("只有昵称");
        assertThat(VALIDATOR.validate(register)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
        register.setNickname(null); update.setNickname(null);
        register.setEmail("Only@example.com"); update.setEmail("Only@example.com");
        assertThat(VALIDATOR.validate(register)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
    }

    @Test
    void nicknameBoundaryIsTheSameForBothEntrances() {
        RegisterDTO register = registration();
        UserProfileUpdateDTO update = new UserProfileUpdateDTO();
        register.setNickname(repeat("飞", 50)); update.setNickname(repeat("飞", 50));
        assertThat(VALIDATOR.validate(register)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
        register.setNickname(repeat("飞", 51)); update.setNickname(repeat("飞", 51));
        assertThat(VALIDATOR.validate(register)).isNotEmpty();
        assertThat(VALIDATOR.validate(update)).isNotEmpty();
    }

    @Test
    void emailLengthAndFormatAreBothChecked() {
        RegisterDTO register = registration();
        UserProfileUpdateDTO update = new UserProfileUpdateDTO();
        // 64 字符本地部分 + @ + 189 字符域名，合计 254。
        String email = repeat("a", 64) + "@" + repeat("b", 63) + "." + repeat("c", 63) + "." + repeat("d", 61);
        register.setEmail(email); update.setEmail(email);
        assertThat(VALIDATOR.validate(register)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
        register.setEmail(email + "d"); update.setEmail(email + "d");
        assertThat(VALIDATOR.validate(register)).isNotEmpty();
        assertThat(VALIDATOR.validate(update)).isNotEmpty();
        register.setEmail("invalid"); update.setEmail("invalid");
        assertThat(VALIDATOR.validate(register)).isNotEmpty();
        assertThat(VALIDATOR.validate(update)).isNotEmpty();
    }

    @Test
    void originalBcryptByteLimitStillApplies() {
        RegisterDTO dto = registration();
        dto.setPassword(repeat("密", 24)); // UTF-8 恰好 72 字节。
        assertThat(VALIDATOR.validate(dto)).isEmpty();
        dto.setPassword(repeat("密", 25)); // 字符数没超 64，但字节数超限。
        assertThat(VALIDATOR.validate(dto)).isNotEmpty();
    }
}