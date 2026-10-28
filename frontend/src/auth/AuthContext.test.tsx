import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AuthProvider, useAuth } from "./AuthContext";
import { BannerGate } from "../banner/BannerGate";
import { authApi } from "../api/auth";
import { bannerApi } from "../api/banner";

vi.mock("../api/auth", () => ({ authApi: { me: vi.fn(), logout: vi.fn() } }));
vi.mock("../api/banner", () => ({
  bannerApi: { get: vi.fn(), acknowledge: vi.fn() },
}));

function Account() {
  const { user, logout } = useAuth();
  return (
    <>
      <span>{user?.username}</span>
      <button onClick={() => void logout()}>Sign out</button>
    </>
  );
}

describe("session reset on logout", () => {
  it("requires consent for the new session and discards the previous user's cached chats", async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    client.setQueryData(["chats"], [{ id: 1, title: "Previous user's conversation" }]);
    vi.mocked(authApi.me).mockResolvedValue({
      id: 1,
      username: "alice",
      firstName: null,
      lastName: null,
      email: null,
    });
    vi.mocked(authApi.logout).mockResolvedValue(undefined);
    vi.mocked(bannerApi.get)
      .mockResolvedValueOnce({ text: "Usage notice", acknowledged: true })
      .mockResolvedValue({ text: "Usage notice", acknowledged: false });
    render(
      <QueryClientProvider client={client}>
        <BannerGate>
          <AuthProvider>
            <Account />
          </AuthProvider>
        </BannerGate>
      </QueryClientProvider>,
    );
    await screen.findByText("alice");
    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await screen.findByRole("heading", { name: "Notice and Consent" });
    expect(screen.getByRole("button", { name: "I Agree" })).toBeInTheDocument();
    await waitFor(() => expect(client.getQueryData(["auth", "me"])).toBeNull());
    expect(client.getQueryData(["chats"])).toBeUndefined();
  });
});
