package com.example.clientragdemo.users.application.domain.service;

import com.example.clientragdemo.shared.exception.ConflictException;

/**
 * A single sign-on login arrived for a username that already belongs to a local (password) account. They are
 * deliberately not linked: whoever controls that name at the identity provider would otherwise take over the
 * local account and everything it owns.
 */
public class LocalAccountConflictException extends ConflictException {

    public LocalAccountConflictException(String username) {
        super("A local account already exists for '" + username + "'; it is not linked to single sign-on");
    }
}
