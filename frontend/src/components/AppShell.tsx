import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";

import { ADMIN_DOMAINS, getAdminDomain, getPageMeta } from "../adminNavigation";
import { useAuth } from "../auth";
import {
  ChatIcon,
  DocumentIcon,
  GridIcon,
  MemoryIcon,
  SearchIcon,
  UsersIcon,
} from "./Icons";

export function AppShell() {
  const { user, logout } = useAuth();
  const location = useLocation();
  const [loggingOut, setLoggingOut] = useState(false);
  const [adminOpen, setAdminOpen] = useState(false);
  const adminButtonRef = useRef<HTMLButtonElement>(null);
  const adminMenuRef = useRef<HTMLDivElement>(null);
  const mainRef = useRef<HTMLElement>(null);
  const initialRouteRef = useRef(true);
  const focusFirstAdminItem = useRef(false);
  const isAdminRoute = location.pathname === "/admin" || location.pathname.startsWith("/admin/");
  const isChatRoute = location.pathname === "/chat";
  const currentPage = getPageMeta(location.pathname);
  const currentAdminDomain = user?.role === "ADMIN" ? getAdminDomain(location.pathname) : null;

  useEffect(() => {
    setAdminOpen(false);
    document.title = `${currentPage.title} | 知境 RAG`;
    if (initialRouteRef.current) {
      initialRouteRef.current = false;
      return;
    }
    globalThis.requestAnimationFrame(() => mainRef.current?.focus({ preventScroll: true }));
  }, [currentPage.title, location.pathname]);

  useEffect(() => {
    if (!adminOpen) {
      return;
    }

    if (focusFirstAdminItem.current) {
      focusFirstAdminItem.current = false;
      adminMenuRef.current?.querySelector<HTMLAnchorElement>('[role="menuitem"]')?.focus();
    }

    function handlePointerDown(event: PointerEvent) {
      const target = event.target;
      if (target instanceof Node && !adminMenuRef.current?.contains(target)) {
        setAdminOpen(false);
      }
    }

    function handleEscape(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") {
        setAdminOpen(false);
        adminButtonRef.current?.focus();
      }
    }

    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleEscape);
    return () => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleEscape);
    };
  }, [adminOpen]);

  async function handleLogout() {
    setLoggingOut(true);
    try {
      await logout();
    } finally {
      setLoggingOut(false);
    }
  }

  function handleAdminButtonKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      focusFirstAdminItem.current = true;
      setAdminOpen(true);
    }
  }

  function handleAdminMenuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
      return;
    }

    const items = Array.from(
      adminMenuRef.current?.querySelectorAll<HTMLAnchorElement>('[role="menuitem"]') ?? [],
    );
    if (items.length === 0) {
      return;
    }

    event.preventDefault();
    const currentIndex = items.indexOf(document.activeElement as HTMLAnchorElement);
    const nextIndex =
      event.key === "Home"
        ? 0
        : event.key === "End"
          ? items.length - 1
          : event.key === "ArrowUp"
            ? (currentIndex - 1 + items.length) % items.length
            : (currentIndex + 1) % items.length;
    items[nextIndex]?.focus();
  }

  return (
    <div className={`app-layout${isChatRoute ? " chat-shell" : ""}`}>
      <a className="skip-link" href="#main-content">跳到主要内容</a>

      <header className="global-header">
        <div className="global-header-inner">
          <NavLink className="brand-lockup global-brand" to="/" aria-label="知境 RAG，前往文档">
            <DocumentIcon className="brand-icon" />
            <span>知境 RAG</span>
          </NavLink>

          <nav className="primary-nav" aria-label="主导航">
            <NavLink to="/" end>
              <GridIcon />
              <span>文档</span>
            </NavLink>
            <NavLink to="/search">
              <SearchIcon />
              <span>检索</span>
            </NavLink>
            <NavLink to="/chat">
              <ChatIcon />
              <span>问答</span>
            </NavLink>
            <NavLink to="/memory">
              <MemoryIcon />
              <span>记忆</span>
            </NavLink>

            {user?.role === "ADMIN" ? (
              <div
                ref={adminMenuRef}
                className="admin-menu"
                onBlur={(event) => {
                  if (!event.currentTarget.contains(event.relatedTarget)) {
                    setAdminOpen(false);
                  }
                }}
              >
                <button
                  ref={adminButtonRef}
                  type="button"
                  className={`admin-menu-trigger ${isAdminRoute || adminOpen ? "active" : ""}`}
                  aria-haspopup="menu"
                  aria-expanded={adminOpen}
                  aria-controls="admin-navigation-menu"
                  onClick={() => setAdminOpen((open) => !open)}
                  onKeyDown={handleAdminButtonKeyDown}
                >
                  <GridIcon />
                  <span>管理</span>
                </button>
                {adminOpen ? (
                  <div
                    id="admin-navigation-menu"
                    className="admin-menu-panel"
                    role="menu"
                    aria-label="管理工具"
                    onKeyDown={handleAdminMenuKeyDown}
                  >
                    {ADMIN_DOMAINS.map((domain) => (
                      <NavLink role="menuitem" to={domain.href} end={domain.href === "/admin"} key={domain.key}>
                        {domain.key === "ACCESS_CONTENT" ? <UsersIcon /> : domain.key === "RETRIEVAL_KNOWLEDGE" ? <SearchIcon /> : <GridIcon />}
                        <span><strong>{domain.title}</strong><small>{domain.subtitle}</small></span>
                      </NavLink>
                    ))}
                  </div>
                ) : null}
              </div>
            ) : null}
          </nav>

          <div className="account-actions">
            <span>
              {user?.username} · {user?.role === "ADMIN" ? "管理员" : "用户"}
            </span>
            <button
              className="text-button"
              type="button"
              onClick={handleLogout}
              disabled={loggingOut}
            >
              {loggingOut ? "退出中" : "退出"}
            </button>
          </div>
        </div>
      </header>

      <div className={`app-main${isChatRoute ? " chat-route" : ""}`}>
        <div className="topbar">
          <div className="topbar-title">
            {currentAdminDomain ? (
              <nav className="admin-breadcrumb" aria-label="面包屑">
                <NavLink to="/admin">管理</NavLink>
                <span aria-hidden="true">/</span>
                <span>{currentAdminDomain.title}</span>
                {currentPage.title !== currentAdminDomain.title ? <><span aria-hidden="true">/</span><span>{currentPage.title}</span></> : null}
              </nav>
            ) : null}
            <h1>{currentPage.title}</h1>
            {currentPage.subtitle ? <p>{currentPage.subtitle}</p> : null}
          </div>
        </div>
        {currentAdminDomain?.pages.length ? (
          <nav className="admin-context-nav" aria-label={`${currentAdminDomain.title}二级导航`}>
            {currentAdminDomain.pages.map((page) => (
              <NavLink key={page.href} to={page.href} end>{page.title}</NavLink>
            ))}
          </nav>
        ) : null}
        <main
          ref={mainRef}
          id="main-content"
          className={`page-content${isChatRoute ? " chat-page-content" : ""}`}
          tabIndex={-1}
        >
          <Outlet />
        </main>
      </div>
    </div>
  );
}
