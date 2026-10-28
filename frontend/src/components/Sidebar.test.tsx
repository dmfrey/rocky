import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Sidebar } from "./Sidebar";
import { chatApi } from "../api/chat";
import { useAuth } from "../auth/AuthContext";
import type { ChatSession } from "../api/types";

vi.mock("../api/chat", () => ({
  chatApi: {
    list: vi.fn(),
    create: vi.fn(),
    get: vi.fn(),
    rename: vi.fn(),
    archive: vi.fn(),
    unarchive: vi.fn(),
    remove: vi.fn(),
    getMessages: vi.fn(),
  },
}));

vi.mock("../auth/AuthContext", () => ({
  useAuth: vi.fn(),
}));

const session: ChatSession = {
  id: 1,
  title: "Test chat",
  archived: false,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

function renderSidebar(path = "/") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Sidebar />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function openMenuFor(name: string) {
  await userEvent.click(
    screen.getByRole("button", {
      name: new RegExp(`options for ${name}`, "i"),
    }),
  );
}

beforeEach(() => {
  vi.mocked(chatApi.list).mockResolvedValue([session]);
  vi.mocked(chatApi.rename).mockResolvedValue({ ...session, title: "Renamed" });
  vi.mocked(chatApi.archive).mockResolvedValue({ ...session, archived: true });
  vi.mocked(chatApi.remove).mockResolvedValue(undefined);
  vi.mocked(useAuth).mockReturnValue({
    user: {
      id: 1,
      username: "alice",
      firstName: null,
      lastName: null,
      email: null,
    },
    isLoading: false,
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    refreshUser: vi.fn(),
  });
});

describe("Sidebar chat session actions", () => {
  it("renames a chat on Enter", async () => {
    renderSidebar();
    await screen.findByText("Test chat");

    await openMenuFor("test chat");
    await userEvent.click(screen.getByRole("menuitem", { name: "Rename" }));

    const input = screen.getByRole("textbox");
    await userEvent.clear(input);
    await userEvent.type(input, "Renamed{Enter}");

    await waitFor(() => expect(chatApi.rename).toHaveBeenCalledWith(1, "Renamed"));
  });

  it("cancels a rename on Escape without saving", async () => {
    renderSidebar();
    await screen.findByText("Test chat");

    await openMenuFor("test chat");
    await userEvent.click(screen.getByRole("menuitem", { name: "Rename" }));

    const input = screen.getByRole("textbox");
    await userEvent.type(input, " more{Escape}");

    expect(chatApi.rename).not.toHaveBeenCalled();
    expect(screen.getByText("Test chat")).toBeInTheDocument();
  });

  it("archives a chat", async () => {
    renderSidebar();
    await screen.findByText("Test chat");

    await openMenuFor("test chat");
    await userEvent.click(screen.getByRole("menuitem", { name: "Archive" }));

    await waitFor(() => expect(chatApi.archive).toHaveBeenCalledWith(1));
  });

  it("asks for confirmation before deleting, and only deletes on confirm", async () => {
    renderSidebar();
    await screen.findByText("Test chat");

    await openMenuFor("test chat");
    await userEvent.click(screen.getByRole("menuitem", { name: "Delete" }));

    const dialog = await screen.findByRole("alertdialog");
    expect(chatApi.remove).not.toHaveBeenCalled();

    await userEvent.click(within(dialog).getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(chatApi.remove).toHaveBeenCalledWith(1));
  });

  it("does not delete when the confirmation is cancelled", async () => {
    renderSidebar();
    await screen.findByText("Test chat");

    await openMenuFor("test chat");
    await userEvent.click(screen.getByRole("menuitem", { name: "Delete" }));

    const dialog = await screen.findByRole("alertdialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(chatApi.remove).not.toHaveBeenCalled();
  });
});

it("marks the selected conversation as the active navigation link", async () => {
  renderSidebar("/chat/1");
  const link = await screen.findByRole("link", { name: "Test chat" });
  expect(link).toHaveAttribute("aria-current", "page");
  expect(link).toHaveClass("is-active");
});
