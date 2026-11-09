package com.example.clientragdemo.users.adapter.in.endpoint;

import com.example.clientragdemo.users.application.domain.model.User;
import com.example.clientragdemo.users.application.port.in.RegisterUserUseCase;
import com.example.clientragdemo.users.application.port.in.RegisterUserUseCase.RegisterUserCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service account creation. Anyone who can reach the app could use it, so it exists only in
 * {@code app.auth.mode=jdbc} and only while {@code app.auth.registration-enabled} is true (the default, so a
 * jdbc deployment can create its first account). Switched off, the endpoint is absent, not merely refusing.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnExpression("'${app.auth.mode:jdbc}'.equalsIgnoreCase('jdbc') and ${app.auth.registration-enabled:true}")
class RegistrationController {

    private final RegisterUserUseCase registerUserUseCase;

    RegistrationController(RegisterUserUseCase registerUserUseCase) {
        this.registerUserUseCase = registerUserUseCase;
    }

    @PostMapping("/register")
    ResponseEntity<UserResponse> register(@RequestBody RegisterRequest request) {
        User user = registerUserUseCase.execute(new RegisterUserCommand(
                request.username(), request.password(), request.firstName(), request.lastName(), request.email()));
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    record RegisterRequest(String username, String password, String firstName, String lastName, String email) {}
}
