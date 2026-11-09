package com.example.clientragdemo.users.application.domain.model;

import java.time.Instant;

public record User(Long id, String username, String passwordHash, String firstName, String lastName, String email, Instant createdAt) {

    /**
     * A user who signs in through single sign-on has no password of their own here (the identity provider
     * owns it), so a missing hash marks an externally authenticated user; such a user can never sign in
     * with a local password.
     */
    public boolean isExternallyAuthenticated() {
        return passwordHash == null;
    }
}
