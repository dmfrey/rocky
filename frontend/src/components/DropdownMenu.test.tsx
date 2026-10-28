import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DropdownMenu, DropdownMenuItem } from "./DropdownMenu";

function Menu({ action = vi.fn() }: { action?: () => void }) {
  return (
    <DropdownMenu
      trigger={({ onClick, open }) => (
        <button onClick={onClick} aria-expanded={open} aria-haspopup="menu">
          Options
        </button>
      )}
    >
      <DropdownMenuItem onClick={action}>Rename</DropdownMenuItem>
      <DropdownMenuItem onClick={vi.fn()}>Archive</DropdownMenuItem>
      <DropdownMenuItem onClick={vi.fn()} danger>
        Delete
      </DropdownMenuItem>
    </DropdownMenu>
  );
}

describe("DropdownMenu keyboard interaction", () => {
  it("opens from the keyboard, moves focus, and returns focus on Escape", async () => {
    render(<Menu />);
    const trigger = screen.getByRole("button", { name: "Options" });
    trigger.focus();
    await userEvent.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitem", { name: "Rename" })).toHaveFocus();
    await userEvent.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitem", { name: "Archive" })).toHaveFocus();
    await userEvent.keyboard("{End}");
    expect(screen.getByRole("menuitem", { name: "Delete" })).toHaveFocus();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("activates an item with Enter and closes the menu", async () => {
    const action = vi.fn();
    render(<Menu action={action} />);
    await userEvent.click(screen.getByRole("button", { name: "Options" }));
    await userEvent.keyboard("{Enter}");
    expect(action).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });
});
