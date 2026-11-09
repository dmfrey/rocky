package com.example.clientragdemo.users.adapter.in.endpoint;

import com.example.clientragdemo.users.application.domain.model.User;
import com.example.clientragdemo.users.application.port.in.UpdateUserProfileUseCase;
import com.example.clientragdemo.users.application.port.in.UpdateUserProfileUseCase.UpdateUserProfileCommand;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
class UserController {

    private final UpdateUserProfileUseCase updateUserProfileUseCase;

    UserController(UpdateUserProfileUseCase updateUserProfileUseCase) {
        this.updateUserProfileUseCase = updateUserProfileUseCase;
    }

    @PatchMapping("/me")
    UserResponse updateProfile(@RequestBody UpdateProfileRequest request, Authentication authentication) {
        User user = updateUserProfileUseCase.execute(new UpdateUserProfileCommand(
                authentication.getName(), request.firstName(), request.lastName(), request.email()));
        return toResponse(user);
    }

    private static UserResponse toResponse(User user) {
        return UserResponse.from(user);
    }

    record UpdateProfileRequest(String firstName, String lastName, String email) {}

}
