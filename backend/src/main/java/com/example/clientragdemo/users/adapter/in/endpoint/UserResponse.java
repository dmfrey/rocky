package com.example.clientragdemo.users.adapter.in.endpoint;

import com.example.clientragdemo.users.application.domain.model.User;

record UserResponse(Long id, String username, String firstName, String lastName, String email) {

    static UserResponse from(User user) {
        return new UserResponse(user.id(), user.username(), user.firstName(), user.lastName(), user.email());
    }
}
