package com.example.clientragdemo.users.application.port.in;

import com.example.clientragdemo.users.application.domain.model.User;

/** Finds, or creates on first login, the {@link User} for someone authenticated by single sign-on. */
public interface ProvisionSsoUserUseCase {

    User execute(ProvisionSsoUserCommand command);

    record ProvisionSsoUserCommand(String username, String firstName, String lastName, String email) {}
}
