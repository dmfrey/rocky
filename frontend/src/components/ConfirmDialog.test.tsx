import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConfirmDialog } from "./ConfirmDialog";

describe("ConfirmDialog", () => {
  it("renders the title and message", () => {
    render(
      <ConfirmDialog title="Delete document?" message="This can't be undone." onConfirm={vi.fn()} onCancel={vi.fn()} />,
    );

    expect(screen.getByText("Delete document?")).toBeInTheDocument();
    expect(screen.getByText("This can't be undone.")).toBeInTheDocument();
  });

  it("calls onConfirm when the confirm button is clicked", async () => {
    const onConfirm = vi.fn();
    render(<ConfirmDialog title="t" message="m" confirmLabel="Delete" onConfirm={onConfirm} onCancel={vi.fn()} />);

    await userEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it("calls onCancel when the cancel button is clicked", async () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog title="t" message="m" onConfirm={vi.fn()} onCancel={onCancel} />);

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("calls onCancel when clicking the backdrop, but not when clicking inside the dialog", async () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog title="t" message="m" onConfirm={vi.fn()} onCancel={onCancel} />);

    await userEvent.click(screen.getByText("t"));
    expect(onCancel).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("alertdialog").parentElement!);
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("calls onCancel when Escape is pressed", async () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog title="t" message="m" onConfirm={vi.fn()} onCancel={onCancel} />);

    await userEvent.keyboard("{Escape}");

    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});

it("keeps keyboard focus inside the dialog and restores it on close", async () => {
  const opener = document.createElement("button");
  document.body.append(opener);
  opener.focus();
  const { unmount } = render(
    <ConfirmDialog title="Delete document?" message="Confirm deletion." onConfirm={vi.fn()} onCancel={vi.fn()} />,
  );
  const cancel = screen.getByRole("button", { name: "Cancel" });
  const confirm = screen.getByRole("button", { name: "Confirm" });
  expect(cancel).toHaveFocus();
  await userEvent.tab({ shift: true });
  expect(confirm).toHaveFocus();
  await userEvent.tab();
  expect(cancel).toHaveFocus();
  unmount();
  expect(opener).toHaveFocus();
  opener.remove();
});
