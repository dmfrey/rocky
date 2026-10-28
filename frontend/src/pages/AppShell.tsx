import { useEffect, useRef, useState } from "react";
import { Link, Outlet, useLocation } from "react-router-dom";
import { Sidebar } from "../components/Sidebar";
import { Icon, RockyMark } from "../components/Icon";

export function AppShell() {
  const location = useLocation();
  const [navigation, setNavigation] = useState({
    path: location.pathname,
    open: false,
  });
  const toggleRef = useRef<HTMLButtonElement>(null);
  const navRef = useRef<HTMLDivElement>(null);
  // Reset during a route transition so returning with Back cannot reopen an old drawer.
  if (navigation.path !== location.pathname)
    setNavigation({ path: location.pathname, open: false });
  const navigationOpen =
    navigation.path === location.pathname && navigation.open;
  function setNavigationOpen(open: boolean) {
    setNavigation({ path: location.pathname, open });
  }
  useEffect(() => {
    if (!navigationOpen) return;
    navRef.current?.querySelector<HTMLElement>("button, a")?.focus();
    function dismiss(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setNavigation((current) => ({ ...current, open: false }));
        toggleRef.current?.focus();
      }
    }
    document.addEventListener("keydown", dismiss);
    return () => document.removeEventListener("keydown", dismiss);
  }, [navigationOpen]);
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <header className="product-header">
        <button
          ref={toggleRef}
          className="icon-button navigation-toggle"
          aria-label={navigationOpen ? "Close navigation" : "Open navigation"}
          aria-expanded={navigationOpen}
          aria-controls="workspace-navigation"
          onClick={() => setNavigationOpen(!navigationOpen)}
        >
          <Icon name={navigationOpen ? "close" : "menu"} />
        </button>
        <Link to="/" className="product-brand">
          <RockyMark />
          <span>Rocky</span>
        </Link>
        <span className="product-description">Knowledge workspace</span>
        <span className="product-header-caption">Document intelligence</span>
      </header>
      <div className="workspace">
        {navigationOpen && (
          <button
            className="navigation-backdrop"
            aria-label="Close navigation"
            onClick={() => {
              setNavigationOpen(false);
              toggleRef.current?.focus();
            }}
          />
        )}
        <div
          ref={navRef}
          id="workspace-navigation"
          className={`navigation-container ${navigationOpen ? "is-open" : ""}`}
        >
          <Sidebar />
        </div>
        <main id="main-content" className="main-content" tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
