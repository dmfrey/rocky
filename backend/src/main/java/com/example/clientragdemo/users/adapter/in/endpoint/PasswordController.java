package com.example.clientragdemo.users.adapter.in.endpoint;

import com.example.clientragdemo.users.application.port.in.ChangePasswordUseCase;
import com.example.clientragdemo.users.application.port.in.ChangePasswordUseCase.ChangePasswordCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Changing a local password. Only exists in {@code app.auth.mode=jdbc}: with SSO the identity provider owns it. */
@RestController
@RequestMapping("/api/users")
@ConditionalOnProperty(prefix = "app.auth", name = "mode", havingValue = "jdbc", matchIfMissing = true)
class PasswordController {

    private final ChangePasswordUseCase changePasswordUseCase;

    PasswordController(ChangePasswordUseCase changePasswordUseCase) {
        this.changePasswordUseCase = changePasswordUseCase;
    }

    @PutMapping("/me/password")
    ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest request, Authentication authentication) {
        changePasswordUseCase.execute(new ChangePasswordCommand(authentication.getName(), request.currentPassword(), request.newPassword()));
        return ResponseEntity.noContent().build();
    }

    record ChangePasswordRequest(String currentPassword, String newPassword) {}
}
