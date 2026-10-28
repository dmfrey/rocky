import { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";

export interface ConfirmDialogProps {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({
  title,
  message,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const id = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef(onCancel);
  useEffect(() => {
    cancelRef.current = onCancel;
  }, [onCancel]);
  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null;
    dialogRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        cancelRef.current();
      }
      if (event.key === "Tab") {
        const buttons = dialogRef.current?.querySelectorAll<HTMLButtonElement>("button");
        if (!buttons?.length) return;
        const first = buttons[0];
        const last = buttons[buttons.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      previousFocus?.focus();
    };
  }, []);
  return createPortal(
    <div className="dialog-backdrop" onClick={onCancel}>
      <div
        ref={dialogRef}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        aria-describedby={`${id}-message`}
        className="confirm-dialog"
        onClick={(event) => event.stopPropagation()}
      >
        <h2 id={`${id}-title`}>{title}</h2>
        <p id={`${id}-message`}>{message}</p>
        <div className="dialog-actions">
          <button type="button" onClick={onCancel} className="btn">
            {cancelLabel}
          </button>
          <button type="button" onClick={onConfirm} className="btn btn-danger">
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
