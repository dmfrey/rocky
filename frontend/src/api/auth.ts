import { api, clearCsrfToken } from "./client";
import type { AuthConfig, LogoutResult, User } from "./types";

export interface RegisterInput {
  username: string;
  password: string;
  firstName: string;
  lastName: string;
  email: string;
}

export const authApi = {
  config: () => api.get<AuthConfig>("/api/auth/config"),
  register: (input: RegisterInput) => api.post<User>("/api/auth/register", input),
  login: async (username: string, password: string) => {
    const user = await api.post<User>("/api/auth/login", { username, password });
    // A new authenticated session may carry a new CSRF token.
    clearCsrfToken();
    return user;
  },
  // 204 in jdbc mode; in oidc mode a { logoutUrl } the browser must navigate to (see AuthContext).
  logout: async () => {
    const result = await api.post<LogoutResult | undefined>("/api/auth/logout");
    clearCsrfToken();
    return result;
  },
  me: () => api.get<User>("/api/auth/me"),
};
