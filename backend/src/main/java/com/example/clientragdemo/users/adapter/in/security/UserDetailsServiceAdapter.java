package com.example.clientragdemo.users.adapter.in.security;

import com.example.clientragdemo.users.application.port.out.LoadUserByUsernamePort;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

@Component
class UserDetailsServiceAdapter implements UserDetailsService {

    private final LoadUserByUsernamePort loadUserByUsernamePort;

    UserDetailsServiceAdapter(LoadUserByUsernamePort loadUserByUsernamePort) {
        this.loadUserByUsernamePort = loadUserByUsernamePort;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var user = loadUserByUsernamePort.loadByUsername(username)
                .filter(found -> !found.isExternallyAuthenticated())
                .orElseThrow(() -> new UsernameNotFoundException(username));

        return User.withUsername(user.username())
                .password(user.passwordHash())
                .authorities("ROLE_USER")
                .build();
    }
}
