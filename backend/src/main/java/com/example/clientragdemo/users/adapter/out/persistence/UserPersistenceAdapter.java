package com.example.clientragdemo.users.adapter.out.persistence;

import com.example.clientragdemo.users.application.domain.model.User;
import com.example.clientragdemo.users.application.port.out.LoadUserByUsernamePort;
import com.example.clientragdemo.users.application.port.out.SaveUserPort;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class UserPersistenceAdapter implements SaveUserPort, LoadUserByUsernamePort {

    /**
     * What {@code users.password_hash} holds for a user who has no local password (single sign-on): the column
     * is {@code NOT NULL}, and the migration that would relax it cannot run where the backend's database role
     * does not own the table (it failed with "must be owner of table users" - the tables were created by
     * {@code pgadmin}, and the per-binding role the app now connects as is not a member). This is not a valid
     * BCrypt hash ({@code $2...}), so no password can ever match it. The domain never sees it: a missing
     * password there is {@code null} ({@link User#isExternallyAuthenticated()}).
     */
    static final String NO_LOCAL_PASSWORD = "{sso}";

    private final UserJdbcRepository repository;

    UserPersistenceAdapter(UserJdbcRepository repository) {
        this.repository = repository;
    }

    @Override
    public User save(User user) {
        UserEntity saved = repository.save(new UserEntity(
                user.id(), user.username(), user.passwordHash() == null ? NO_LOCAL_PASSWORD : user.passwordHash(), user.firstName(), user.lastName(), user.email(), user.createdAt()));
        return toDomain(saved);
    }

    @Override
    public Optional<User> loadByUsername(String username) {
        return repository.findByUsername(username).map(UserPersistenceAdapter::toDomain);
    }

    private static User toDomain(UserEntity entity) {
        String passwordHash = NO_LOCAL_PASSWORD.equals(entity.passwordHash()) ? null : entity.passwordHash();
        return new User(entity.id(), entity.username(), passwordHash, entity.firstName(), entity.lastName(), entity.email(), entity.createdAt());
    }
}
