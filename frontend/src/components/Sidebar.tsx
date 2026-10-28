import { useState, type KeyboardEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { NavLink, useLocation, useNavigate } from "react-router-dom";
import { chatApi } from "../api/chat";
import { ConfirmDialog } from "./ConfirmDialog";
import { DropdownMenu, DropdownMenuItem } from "./DropdownMenu";
import { UserMenu } from "./UserMenu";
import { Icon } from "./Icon";
import type { ChatSession } from "../api/types";

export function Sidebar() {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();

  const sessionsQuery = useQuery({
    queryKey: ["chats"],
    queryFn: () => chatApi.list(false),
  });

  const [renamingId, setRenamingId] = useState<number | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [pendingDelete, setPendingDelete] = useState<ChatSession | null>(null);

  const createChatMutation = useMutation({
    mutationFn: chatApi.create,
    onSuccess: (session) => {
      queryClient.invalidateQueries({ queryKey: ["chats"] });
      navigate(`/chat/${session.id}`);
    },
  });

  const renameMutation = useMutation({
    mutationFn: ({ id, title }: { id: number; title: string }) => chatApi.rename(id, title),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["chats"] });
      setRenamingId(null);
    },
  });

  const archiveMutation = useMutation({
    mutationFn: (id: number) => chatApi.archive(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["chats"] }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => chatApi.remove(id),
    onSuccess: (_data, id) => {
      queryClient.invalidateQueries({ queryKey: ["chats"] });
      if (location.pathname === `/chat/${id}`) {
        navigate("/", { replace: true });
      }
    },
  });

  function startRename(session: ChatSession) {
    setRenamingId(session.id);
    setRenameValue(session.title ?? "");
  }

  function commitRename(id: number) {
    const title = renameValue.trim();
    if (!title) {
      setRenamingId(null);
      return;
    }
    renameMutation.mutate({ id, title });
  }

  function handleRenameKeyDown(event: KeyboardEvent<HTMLInputElement>, id: number) {
    if (event.key === "Enter") {
      event.preventDefault();
      commitRename(id);
    } else if (event.key === "Escape") {
      event.preventDefault();
      setRenamingId(null);
    }
  }

  const linkClasses = ({ isActive }: { isActive: boolean }) => `navigation-link ${isActive ? "is-active" : ""}`;

  return (
    <aside className="sidebar" aria-label="Workspace">
      <div className="sidebar-create">
        <button
          type="button"
          onClick={() => createChatMutation.mutate()}
          disabled={createChatMutation.isPending}
          className="btn btn-primary w-full"
        >
          <Icon name="plus" size={16} /> New chat
        </button>
      </div>

      <nav className="sidebar-links" aria-label="Workspace navigation">
        <NavLink to="/" end className={linkClasses}>
          <Icon name="chat" /> Overview
        </NavLink>
        <NavLink to="/documents" className={linkClasses}>
          <Icon name="documents" /> Documents
        </NavLink>
        <NavLink to="/settings" className={linkClasses}>
          <Icon name="settings" /> Settings
        </NavLink>
      </nav>
      <div className="sidebar-section-label">Conversations</div>
      <nav className="conversation-navigation" aria-label="Conversations">
        {sessionsQuery.isLoading && (
          <p className="sidebar-hint" role="status">
            Loading conversations…
          </p>
        )}
        {sessionsQuery.isError && (
          <p className="sidebar-hint" role="alert">
            Could not load conversations.
          </p>
        )}
        {sessionsQuery.data?.length === 0 && <p className="sidebar-hint">Your conversations will appear here.</p>}
        <ul className="flex flex-col gap-0.5">
          {sessionsQuery.data?.map((session) =>
            renamingId === session.id ? (
              <li key={session.id} className="rename-row">
                <input
                  autoFocus
                  value={renameValue}
                  onChange={(event) => setRenameValue(event.target.value)}
                  onKeyDown={(event) => handleRenameKeyDown(event, session.id)}
                  onBlur={() => commitRename(session.id)}
                  onFocus={(event) => event.target.select()}
                  className="form-control"
                  aria-label="Chat title"
                />
              </li>
            ) : (
              <li key={session.id} className="conversation-row">
                <NavLink
                  to={`/chat/${session.id}`}
                  className={({ isActive }) => `conversation-link ${isActive ? "is-active" : ""}`}
                >
                  {session.title ?? "New chat"}
                </NavLink>
                <DropdownMenu
                  trigger={({ onClick, open }) => (
                    <button
                      type="button"
                      onClick={onClick}
                      aria-haspopup="menu"
                      aria-expanded={open}
                      aria-label={`Options for ${session.title ?? "New chat"}`}
                      className="icon-button conversation-options"
                    >
                      <Icon name="more" size={18} />
                    </button>
                  )}
                >
                  <DropdownMenuItem onClick={() => startRename(session)}>Rename</DropdownMenuItem>
                  <DropdownMenuItem onClick={() => archiveMutation.mutate(session.id)}>Archive</DropdownMenuItem>
                  <DropdownMenuItem danger onClick={() => setPendingDelete(session)}>
                    Delete
                  </DropdownMenuItem>
                </DropdownMenu>
              </li>
            ),
          )}
        </ul>
      </nav>

      {(createChatMutation.isError || renameMutation.isError || archiveMutation.isError || deleteMutation.isError) && (
        <p className="sidebar-hint error-text" role="alert">
          Could not update the conversation. Please try again.
        </p>
      )}
      <div className="sidebar-account">
        <UserMenu />
      </div>

      {pendingDelete && (
        <ConfirmDialog
          title="Delete chat?"
          message={`Delete "${pendingDelete.title ?? "New chat"}"? This can't be undone.`}
          confirmLabel="Delete"
          onConfirm={() => {
            deleteMutation.mutate(pendingDelete.id);
            setPendingDelete(null);
          }}
          onCancel={() => setPendingDelete(null)}
        />
      )}
    </aside>
  );
}
