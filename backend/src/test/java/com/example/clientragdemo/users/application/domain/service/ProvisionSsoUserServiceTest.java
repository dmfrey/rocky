package com.example.clientragdemo.users.application.domain.service;

import com.example.clientragdemo.users.application.domain.model.User;
import com.example.clientragdemo.users.application.port.in.ProvisionSsoUserUseCase.ProvisionSsoUserCommand;
import com.example.clientragdemo.users.application.port.out.LoadUserByUsernamePort;
import com.example.clientragdemo.users.application.port.out.SaveUserPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProvisionSsoUserServiceTest {

    // The ports are two methods each - a map stands in for the table.
    private final Map<String, User> users = new HashMap<>();
    private final LoadUserByUsernamePort load = username -> Optional.ofNullable(users.get(username));
    private final SaveUserPort save = user -> {
        User saved = new User(user.id() == null ? (long) users.size() + 1 : user.id(), user.username(), user.passwordHash(),
                user.firstName(), user.lastName(), user.email(), user.createdAt());
        users.put(saved.username(), saved);
        return saved;
    };
    private final ProvisionSsoUserService service = new ProvisionSsoUserService(load, save);

    @Test
    void createsAPasswordlessUserOnFirstLogin() {
        User user = service.execute(new ProvisionSsoUserCommand("dana", "Dana", "Doe", "dana@example.com"));

        assertThat(user.id()).isNotNull();
        assertThat(user.username()).isEqualTo("dana");
        assertThat(user.passwordHash()).isNull();
        assertThat(user.isExternallyAuthenticated()).isTrue();
        assertThat(user.firstName()).isEqualTo("Dana");
        assertThat(user.email()).isEqualTo("dana@example.com");
    }

    @Test
    void returnsTheExistingSsoUserUnchangedOnLaterLogins() {
        User first = service.execute(new ProvisionSsoUserCommand("dana", "Dana", "Doe", "dana@example.com"));
        // The user edited their profile here since; a later login must not overwrite it from the claims.
        users.put("dana", new User(first.id(), "dana", null, "Danielle", "Doe", "dana@example.com", first.createdAt()));

        User again = service.execute(new ProvisionSsoUserCommand("dana", "Dana", "Doe", "other@example.com"));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.firstName()).isEqualTo("Danielle");
        assertThat(again.email()).isEqualTo("dana@example.com");
        assertThat(users).hasSize(1);
    }

    @Test
    void refusesToTakeOverALocalAccountWithTheSameUsername() {
        users.put("dana", new User(1L, "dana", "$2a$10$hash", null, null, null, Instant.now()));

        assertThatThrownBy(() -> service.execute(new ProvisionSsoUserCommand("dana", "Dana", "Doe", "dana@example.com")))
                .isInstanceOf(LocalAccountConflictException.class);
        assertThat(users.get("dana").passwordHash()).isEqualTo("$2a$10$hash");
    }

    @Test
    void rejectsABlankUsername() {
        assertThatThrownBy(() -> service.execute(new ProvisionSsoUserCommand(" ", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
