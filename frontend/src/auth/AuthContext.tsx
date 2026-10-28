import { createContext, useContext, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { authApi, type RegisterInput } from "../api/auth";
import type { User } from "../api/types";

interface AuthContextValue {
  user: User | null;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  register: (input: RegisterInput) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: (user: User) => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

const ME_QUERY_KEY = ["auth", "me"];

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();

  // A 401 here just means "not logged in" - not retried, and not treated as an app-level error.
  const meQuery = useQuery({
    queryKey: ME_QUERY_KEY,
    queryFn: authApi.me,
    retry: false,
  });

  const loginMutation = useMutation({
    mutationFn: ({
      username,
      password,
    }: {
      username: string;
      password: string;
    }) => authApi.login(username, password),
    onSuccess: (user) => queryClient.setQueryData(ME_QUERY_KEY, user),
  });

  const registerMutation = useMutation({
    mutationFn: (input: RegisterInput) => authApi.register(input),
  });

  const logoutMutation = useMutation({
    mutationFn: authApi.logout,
    onSuccess: async () => {
      // Logout invalidates the server session, including its consent acknowledgment.
      // Discard the previous user's cached content and show the new session's notice.
      await queryClient.cancelQueries();
      queryClient.removeQueries({
        predicate: (query) =>
          !["auth", "banner"].includes(String(query.queryKey[0])),
      });
      queryClient.setQueryData(ME_QUERY_KEY, null);
      await queryClient.resetQueries({ queryKey: ["banner"] });
    },
  });

  const value: AuthContextValue = {
    user: meQuery.data ?? null,
    isLoading: meQuery.isLoading,
    login: async (username, password) => {
      await loginMutation.mutateAsync({ username, password });
    },
    register: async (input) => {
      await registerMutation.mutateAsync(input);
    },
    logout: async () => {
      await logoutMutation.mutateAsync();
    },
    refreshUser: (user) => queryClient.setQueryData(ME_QUERY_KEY, user),
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
