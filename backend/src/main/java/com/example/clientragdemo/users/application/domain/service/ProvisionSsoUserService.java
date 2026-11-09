package com.example.clientragdemo.users.application.domain.service;

import com.example.clientragdemo.users.application.domain.model.User;
import com.example.clientragdemo.users.application.port.in.ProvisionSsoUserUseCase;
import com.example.clientragdemo.users.application.port.out.LoadUserByUsernamePort;
import com.example.clientragdemo.users.application.port.out.SaveUserPort;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
class ProvisionSsoUserService implements ProvisionSsoUserUseCase {

    private final LoadUserByUsernamePort loadUserByUsernamePort;
    private final SaveUserPort saveUserPort;

    ProvisionSsoUserService(LoadUserByUsernamePort loadUserByUsernamePort, SaveUserPort saveUserPort) {
        this.loadUserByUsernamePort = loadUserByUsernamePort;
        this.saveUserPort = saveUserPort;
    }

    @Override
    public User execute(ProvisionSsoUserCommand command) {
        if (command.username() == null || command.username().isBlank()) {
            throw new IllegalArgumentException("The identity provider returned no username");
        }

        return loadUserByUsernamePort.loadByUsername(command.username())
                .map(existing -> {
                    if (!existing.isExternallyAuthenticated()) {
                        throw new LocalAccountConflictException(command.username());
                    }
                    // Profile fields are not refreshed from the claims on every login: the user may have
                    // edited them here since.
                    return existing;
                })
                .orElseGet(() -> saveUserPort.save(new User(null, command.username(), null,
                        blankToNull(command.firstName()), blankToNull(command.lastName()),
                        blankToNull(command.email()), Instant.now())));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
