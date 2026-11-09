package com.example.clientragdemo.users.adapter.in.endpoint;

import com.example.clientragdemo.users.application.domain.model.User;
import com.example.clientragdemo.users.application.port.in.GetUserUseCase;
import com.example.clientragdemo.users.application.port.in.GetUserUseCase.GetUserQuery;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user, in either sign-in mode. Local login/registration live in their own controllers. */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final GetUserUseCase getUserUseCase;

    AuthController(GetUserUseCase getUserUseCase) {
        this.getUserUseCase = getUserUseCase;
    }

    @GetMapping("/me")
    ResponseEntity<UserResponse> me(Authentication authentication) {
        User user = getUserUseCase.execute(new GetUserQuery(authentication.getName()));
        return ResponseEntity.ok(UserResponse.from(user));
    }
}
