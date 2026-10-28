import { Icon } from "./Icon";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { DropdownMenu, DropdownMenuItem } from "./DropdownMenu";

export function UserMenu() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate("/login", { replace: true });
  }

  return (
    <DropdownMenu
      align="left"
      position="top"
      trigger={({ onClick, open }) => (
        <button
          type="button"
          onClick={onClick}
          aria-haspopup="menu"
          aria-expanded={open}
          aria-label={`Account menu for ${user?.username ?? "user"}`}
          className="account-trigger"
        >
          <span className="account-avatar">
            <Icon name="user" size={16} />
          </span>
          <span className="truncate">{user?.username}</span>
          <Icon name="more" size={18} />
        </button>
      )}
    >
      <DropdownMenuItem onClick={() => navigate("/settings")}>
        <Icon name="settings" size={16} /> Settings
      </DropdownMenuItem>
      <DropdownMenuItem onClick={() => void handleLogout()}>
        Log out
      </DropdownMenuItem>
    </DropdownMenu>
  );
}
