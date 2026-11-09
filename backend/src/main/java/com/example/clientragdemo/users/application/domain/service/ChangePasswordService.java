package com.example.clientragdemo.users.application.domain.service;

import com.example.clientragdemo.users.application.domain.model.User;
import com.example.clientragdemo.users.application.port.in.ChangePasswordUseCase;
import com.example.clientragdemo.users.application.port.out.LoadUserByUsernamePort;
import com.example.clientragdemo.users.application.port.out.SaveUserPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class ChangePasswordService implements ChangePasswordUseCase {

    private final LoadUserByUsernamePort loadUserByUsernamePort;
    private final SaveUserPort saveUserPort;
    private final PasswordEncoder passwordEncoder;

    ChangePasswordService(LoadUserByUsernamePort loadUserByUsernamePort, SaveUserPort saveUserPort, PasswordEncoder passwordEncoder) {
        this.loadUserByUsernamePort = loadUserByUsernamePort;
        this.saveUserPort = saveUserPort;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void execute(ChangePasswordCommand command) {
        User user = loadUserByUsernamePort.loadByUsername(command.username())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + command.username()));

        if (user.isExternallyAuthenticated()) {
            throw new IllegalArgumentException("This account signs in through single sign-on and has no password here");
        }
        if (!passwordEncoder.matches(command.currentPassword(), user.passwordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (command.newPassword() == null || command.newPassword().isBlank() || command.newPassword().length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }

        User updated = new User(user.id(), user.username(), passwordEncoder.encode(command.newPassword()),
                user.firstName(), user.lastName(), user.email(), user.createdAt());

        saveUserPort.save(updated);
    }
}
