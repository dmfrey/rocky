import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";

export interface DropdownMenuProps {
  trigger: (props: { onClick: (event: React.MouseEvent) => void; open: boolean }) => ReactNode;
  children: ReactNode;
  align?: "left" | "right";
  position?: "top" | "bottom";
}

export function DropdownMenu({ trigger, children, align = "right", position = "bottom" }: DropdownMenuProps) {
  const [open, setOpen] = useState(false);
  const [offset, setOffset] = useState({ top: 0, left: 0 });
  const containerRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  function closeMenu(restoreFocus = true) {
    setOpen(false);
    if (restoreFocus) containerRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
  }

  useLayoutEffect(() => {
    if (!open) return;
    function placeMenu() {
      const anchor = containerRef.current?.getBoundingClientRect();
      const menu = menuRef.current?.getBoundingClientRect();
      if (!anchor || !menu) return;
      const preferredTop = position === "top" ? anchor.top - menu.height - 4 : anchor.bottom + 4;
      setOffset({
        top: Math.max(8, Math.min(preferredTop, window.innerHeight - menu.height - 8)),
        left: Math.max(
          8,
          Math.min(align === "right" ? anchor.right - menu.width : anchor.left, window.innerWidth - menu.width - 8),
        ),
      });
    }
    placeMenu();
    menuRef.current?.querySelector<HTMLButtonElement>("[role=menuitem]")?.focus();
    window.addEventListener("resize", placeMenu);
    window.addEventListener("scroll", placeMenu, true);
    return () => {
      window.removeEventListener("resize", placeMenu);
      window.removeEventListener("scroll", placeMenu, true);
    };
  }, [open, align, position]);

  useEffect(() => {
    if (!open) return;
    function dismiss(event: MouseEvent) {
      const target = event.target as Node;
      if (!containerRef.current?.contains(target) && !menuRef.current?.contains(target)) setOpen(false);
    }
    document.addEventListener("mousedown", dismiss);
    return () => document.removeEventListener("mousedown", dismiss);
  }, [open]);

  return (
    <div
      ref={containerRef}
      className="relative inline-block"
      onKeyDown={(event) => {
        if (!open && event.key === "ArrowDown") {
          event.preventDefault();
          setOpen(true);
        }
      }}
    >
      {trigger({
        open,
        onClick: (event) => {
          event.stopPropagation();
          event.preventDefault();
          setOpen(!open);
        },
      })}
      {open &&
        createPortal(
          <div
            ref={menuRef}
            role="menu"
            className="dropdown-panel"
            style={offset}
            onClick={() => closeMenu()}
            onKeyDown={(event) => {
              const items = Array.from(menuRef.current?.querySelectorAll<HTMLButtonElement>("[role=menuitem]") ?? []);
              const current = items.indexOf(document.activeElement as HTMLButtonElement);
              if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
                event.preventDefault();
                const next =
                  event.key === "Home"
                    ? 0
                    : event.key === "End"
                      ? items.length - 1
                      : (current + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
                items[next]?.focus();
              } else if (event.key === "Escape") {
                event.preventDefault();
                event.stopPropagation();
                closeMenu();
              } else if (event.key === "Tab") closeMenu();
            }}
          >
            {children}
          </div>,
          document.body,
        )}
    </div>
  );
}

export function DropdownMenuItem({
  onClick,
  danger,
  children,
}: {
  onClick: () => void;
  danger?: boolean;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      role="menuitem"
      tabIndex={-1}
      onClick={onClick}
      className={`dropdown-item ${danger ? "is-danger" : ""}`}
    >
      {children}
    </button>
  );
}
