package com.example.clientragdemo.users.adapter.in.security;

import com.example.clientragdemo.shared.exception.ConflictException;
import com.example.clientragdemo.users.application.port.in.ProvisionSsoUserUseCase;
import com.example.clientragdemo.users.application.port.in.ProvisionSsoUserUseCase.ProvisionSsoUserCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Driven by Spring Security's OIDC login (picked up as the {@code OAuth2UserService<OidcUserRequest, OidcUser>}
 * bean): after the identity provider has authenticated someone, makes sure a {@code users} row exists for
 * them so the rest of the app (profile, chat and document ownership) works exactly as for a local account.
 * Only exists in {@code app.auth.mode=oidc}.
 */
@Component
@ConditionalOnProperty(prefix = "app.auth", name = "mode", havingValue = "oidc")
class SsoOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    static final String CONFLICT_ERROR_CODE = "local_account_conflict";

    private final OidcUserService delegate = new OidcUserService();
    private final ProvisionSsoUserUseCase provisionSsoUserUseCase;

    SsoOidcUserService(ProvisionSsoUserUseCase provisionSsoUserUseCase) {
        this.provisionSsoUserUseCase = provisionSsoUserUseCase;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = delegate.loadUser(userRequest);
        try {
            // getName() is the claim configured as the registration's user-name-attribute (user_name for
            // the Tanzu SSO tile) - the same string every other feature uses as the owner key.
            provisionSsoUserUseCase.execute(new ProvisionSsoUserCommand(
                    oidcUser.getName(), oidcUser.getGivenName(), oidcUser.getFamilyName(), oidcUser.getEmail()));
        } catch (ConflictException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error(CONFLICT_ERROR_CODE, e.getMessage(), null), e);
        } catch (IllegalArgumentException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user", e.getMessage(), null), e);
        }
        return oidcUser;
    }
}
