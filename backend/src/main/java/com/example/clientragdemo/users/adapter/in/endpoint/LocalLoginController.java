package com.example.clientragdemo.users.adapter.in.endpoint;

import com.example.clientragdemo.users.application.domain.model.User;
import com.example.clientragdemo.users.application.port.in.GetUserUseCase;
import com.example.clientragdemo.users.application.port.in.GetUserUseCase.GetUserQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Username/password sign-in. Only exists in {@code app.auth.mode=jdbc}. */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "app.auth", name = "mode", havingValue = "jdbc", matchIfMissing = true)
class LocalLoginController {

    private final GetUserUseCase getUserUseCase;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    LocalLoginController(GetUserUseCase getUserUseCase, AuthenticationManager authenticationManager,
                         SecurityContextRepository securityContextRepository) {
        this.getUserUseCase = getUserUseCase;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/login")
    ResponseEntity<UserResponse> login(@RequestBody AuthRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authenticationRequest = UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password());
        Authentication authenticationResult = authenticationManager.authenticate(authenticationRequest);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticationResult);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        User user = getUserUseCase.execute(new GetUserQuery(authenticationResult.getName()));
        return ResponseEntity.ok(UserResponse.from(user));
    }

    record AuthRequest(String username, String password) {}
}
