package cn.kokonexus.gateway.interfaces;

import static org.assertj.core.api.Assertions.*;

import cn.kokonexus.api.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/** 合成canary，不使用真实密码；日志脱敏不能破坏HTTP/RPC数据访问契约。 */
class SensitiveAuthenticationViewsTest {

    @Test
    void sensitiveRecordsNeverIncludePasswordCookieOrEmailInDiagnosticText() throws Exception {
        String secret = "synthetic-auth-private-canary",
            email = "fixture@example.invalid";
        var request = new AuthController.RegisterRequest(email, secret, "fixture", "合成用户");
        var login = new AuthController.LoginRequest(email, secret);
        var response = new AuthController.AuthResponse(
            "synthetic-cookie",
            secret,
            120,
            new UserIdentity("1", email, "fixture", "合成用户", null)
        );
        for (Object value : new Object[] {
            request,
            login,
            response,
            new AuthenticateIdentityCommand(email, secret),
            new RegisterIdentityCommand(email, secret, "fixture", "合成用户"),
        }) {
            assertThat(value.toString()).doesNotContain(secret, email).contains("redacted");
        }
        var mapper = new ObjectMapper();
        assertThat(mapper.readTree(mapper.writeValueAsBytes(request)).path("password").asText()).isEqualTo(secret);
        assertThat(mapper.readTree(mapper.writeValueAsBytes(response)).path("tokenValue").asText()).isEqualTo(secret);
        assertThat(login.password()).isEqualTo(secret);
    }

    @Test
    void nullRegistrationPasswordAndHandleAreRejectedBeforeCallingIdentityRpc() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory
                .getValidator()
                .validate(new AuthController.RegisterRequest("fixture@example.invalid", null, null, "合成用户"));
            assertThat(violations.stream().map(value -> value.getPropertyPath().toString())).contains(
                "password",
                "handle"
            );
        }
    }
}
