import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import { CloseIcon, SearchIcon } from "../components/Icons";
import { OperationImpactPanel } from "../components/OperationImpactPanel";
import type { ManagedUser, OperationImpact, UserRole } from "../types";

type DrawerMode =
  | { kind: "create" }
  | { kind: "edit"; user: ManagedUser }
  | { kind: "password"; user: ManagedUser };

interface UserDrawerProps {
  mode: DrawerMode;
  onClose: () => void;
  onSaved: (user: ManagedUser, created: boolean) => void;
  onPasswordReset: (username: string) => void;
}

function UserDrawer({ mode, onClose, onSaved, onPasswordReset }: UserDrawerProps) {
  const { expireSession } = useAuth();
  const dialogRef = useRef<HTMLElement>(null);
  const editingUser = mode.kind === "create" ? null : mode.user;
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<UserRole>(editingUser?.role ?? "USER");
  const [enabled, setEnabled] = useState(editingUser?.enabled ?? true);
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [reason, setReason] = useState("");
  const [impact, setImpact] = useState<OperationImpact | null>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) {
      return;
    }
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusableSelector = "button:not([disabled]), input:not([disabled]), select:not([disabled])";
    const focusableElements = () => Array.from(dialog.querySelectorAll<HTMLElement>(focusableSelector));
    dialog.querySelector<HTMLElement>("input:not([disabled]), select:not([disabled])")?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
        return;
      }
      if (event.key !== "Tab") {
        return;
      }
      const elements = focusableElements();
      if (elements.length === 0) {
        event.preventDefault();
        return;
      }
      const first = elements[0];
      const last = elements[elements.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
    dialog.addEventListener("keydown", handleKeyDown);
    return () => {
      dialog.removeEventListener("keydown", handleKeyDown);
      previousFocus?.focus();
    };
  }, [onClose]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setFields({});

    if (mode.kind !== "edit" && password.length < 8) {
      setFields({ password: "密码至少需要 8 位" });
      return;
    }
    if (mode.kind !== "edit" && new TextEncoder().encode(password).length > 72) {
      setFields({ password: "密码不能超过 72 字节" });
      return;
    }

    const requiresImpact = mode.kind !== "create" && editingUser?.securityVersion !== undefined;
    if (requiresImpact && !impact) {
      setSubmitting(true);
      try {
        const preview = await apiRequest<OperationImpact>("/api/v1/admin/operation-impact/preflight", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            operation: mode.kind === "edit" ? "USER_UPDATE" : "USER_PASSWORD_RESET",
            objectId: editingUser.id,
            parameters: mode.kind === "edit" ? { role, enabled } : {},
          }),
        });
        setImpact(preview);
      } catch (caught) {
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setError(caught instanceof ApiError ? caught.message : "影响预检失败，请稍后重试");
      } finally {
        setSubmitting(false);
      }
      return;
    }
    if (requiresImpact && (reason.trim().length < 8 || impact?.blockers.length)) {
      setError(impact?.blockers[0] ?? "审计理由至少需要 8 个字符");
      return;
    }

    setSubmitting(true);
    try {
      if (mode.kind === "create") {
        const created = await apiRequest<ManagedUser>("/api/v1/admin/users", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username, password, role, reason: "管理员创建本地用户" }),
        });
        onSaved(created, true);
      } else if (mode.kind === "edit") {
        const updated = await apiRequest<ManagedUser>(`/api/v1/admin/users/${mode.user.id}`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            role,
            enabled,
            expectedSecurityVersion: impact?.factVersion,
            confirmation: impact?.confirmation,
            reason: reason.trim() || undefined,
          }),
        });
        onSaved(updated, false);
      } else {
        await apiRequest<void>(`/api/v1/admin/users/${mode.user.id}/reset-password`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            password,
            expectedSecurityVersion: impact?.factVersion,
            confirmation: impact?.confirmation,
            reason: reason.trim() || undefined,
          }),
        });
        onPasswordReset(mode.user.username);
      }
      onClose();
    } catch (caught) {
      if (caught instanceof ApiError) {
        if (caught.status === 401) {
          expireSession();
          return;
        }
        setError(caught.message);
        setFields(caught.fields);
      } else {
        setError("保存失败，请稍后重试");
      }
    } finally {
      setSubmitting(false);
    }
  }

  const title = mode.kind === "create"
    ? "创建用户"
    : mode.kind === "edit"
      ? `编辑 ${mode.user.username}`
      : `重置 ${mode.user.username} 的密码`;

  return (
    <div className="drawer-layer">
      <aside ref={dialogRef} className="drawer" aria-labelledby="user-drawer-title" aria-modal="true" role="dialog">
        <header className="drawer-header">
          <h2 id="user-drawer-title">{title}</h2>
          <button className="icon-button" type="button" onClick={onClose} aria-label="关闭">
            <CloseIcon />
          </button>
        </header>

        <form className="drawer-form" onSubmit={handleSubmit}>
          <div className="drawer-body">
            {mode.kind === "create" ? (
              <>
                <label htmlFor="new-username">用户名 <span aria-hidden="true">*</span></label>
                <input
                  id="new-username"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  autoComplete="off"
                  maxLength={50}
                  aria-invalid={Boolean(fields.username)}
                  required
                />
                {fields.username ? <p className="field-error">{fields.username}</p> : null}
              </>
            ) : null}

            {mode.kind !== "edit" ? (
              <>
                <label htmlFor="account-password">
                  {mode.kind === "create" ? "初始密码" : "新密码"} <span aria-hidden="true">*</span>
                </label>
                <input
                  id="account-password"
                  type="password"
                  value={password}
                  onChange={(event) => { setPassword(event.target.value); setImpact(null); }}
                  autoComplete="new-password"
                  aria-describedby="password-help"
                  aria-invalid={Boolean(fields.password)}
                  required
                />
                <p id="password-help" className="field-help">至少 8 位，最长 72 字节</p>
                {fields.password ? <p className="field-error">{fields.password}</p> : null}
              </>
            ) : null}

            {mode.kind !== "password" ? (
              <>
                <label htmlFor="account-role">角色 <span aria-hidden="true">*</span></label>
                <select
                  id="account-role"
                  value={role}
                  onChange={(event) => { setRole(event.target.value as UserRole); setImpact(null); }}
                >
                  <option value="USER">普通用户</option>
                  <option value="ADMIN">管理员</option>
                </select>
                {mode.kind === "edit" ? (
                  <p className="field-help">修改角色后，该用户的现有登录会话会立即失效</p>
                ) : null}
              </>
            ) : null}

            {mode.kind === "edit" ? (
              <label className="toggle-row">
                <input
                  type="checkbox"
                  checked={enabled}
                  onChange={(event) => { setEnabled(event.target.checked); setImpact(null); }}
                />
                <span>
                  <strong>允许登录</strong>
                  <small>关闭后，现有登录会话立即失效，并禁止再次登录</small>
                </span>
              </label>
            ) : null}

            {mode.kind !== "create" && editingUser?.securityVersion !== undefined ? (
              <>
                <label htmlFor="user-operation-reason">审计理由 <span aria-hidden="true">*</span></label>
                <input id="user-operation-reason" value={reason} onChange={(event) => setReason(event.target.value)} minLength={8} maxLength={500} placeholder="说明此次安全操作的原因" required />
                {impact ? <OperationImpactPanel impact={impact} /> : null}
              </>
            ) : null}

            {error ? <div className="form-error drawer-error" role="alert">{error}</div> : null}
          </div>

          <footer className="drawer-footer">
            <button className="secondary-button" type="button" onClick={onClose}>取消</button>
            <button
              className="primary-button"
              type="submit"
              disabled={submitting
                || (mode.kind === "create" && !username.trim())
                || (impact !== null && impact.blockers.length > 0)
                || (mode.kind !== "create" && editingUser?.securityVersion !== undefined && impact !== null && reason.trim().length < 8)}
            >
              {submitting
                ? "处理中"
                : mode.kind === "create"
                  ? "创建"
                  : editingUser?.securityVersion !== undefined && !impact
                    ? "查看影响"
                    : "确认并执行"}
            </button>
          </footer>
        </form>
      </aside>
    </div>
  );
}

export function UsersPage() {
  const { expireSession } = useAuth();
  const [users, setUsers] = useState<ManagedUser[]>([]);
  const [search, setSearch] = useState("");
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [drawer, setDrawer] = useState<DrawerMode | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const loadUsers = useCallback((signal?: AbortSignal) => {
    setState("loading");
    apiRequest<ManagedUser[]>("/api/v1/admin/users", { signal })
      .then((items) => {
        setUsers(items);
        setState("ready");
      })
      .catch((error: unknown) => {
        if (signal?.aborted) {
          return;
        }
        if (error instanceof ApiError && error.status === 401) {
          expireSession();
          return;
        }
        setState("error");
      });
  }, [expireSession]);

  useEffect(() => {
    const controller = new AbortController();
    loadUsers(controller.signal);
    return () => controller.abort();
  }, [loadUsers]);

  const normalizedSearch = search.trim().toLowerCase();
  const visibleUsers = normalizedSearch
    ? users.filter((user) => user.username.toLowerCase().includes(normalizedSearch))
    : users;

  function handleSaved(saved: ManagedUser, created: boolean) {
    setUsers((current) => {
      const next = created
        ? [...current, saved]
        : current.map((user) => user.id === saved.id ? saved : user);
      return next.sort((left, right) => left.username.localeCompare(right.username));
    });
    setMessage(created ? `已创建用户 ${saved.username}` : `已更新用户 ${saved.username}`);
  }

  return (
    <section className="users-page">
      <div className="users-toolbar">
        <label className="search-field">
          <SearchIcon />
          <span className="sr-only">搜索用户名</span>
          <input
            type="search"
            placeholder="搜索用户名"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        <button className="primary-button" type="button" onClick={() => setDrawer({ kind: "create" })}>
          创建用户
        </button>
      </div>

      {message ? <div className="success-message" role="status">{message}</div> : null}

      <div className="table-wrap users-table-wrap">
        <table className="users-table">
          <thead>
            <tr>
              <th>用户名</th>
              <th>角色</th>
              <th>状态</th>
              <th>访问范围</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {visibleUsers.map((user) => (
              <tr key={user.id}>
                <td><Link to={`/admin/users/${user.id}`}><strong>{user.username}</strong></Link></td>
                <td>{user.role}</td>
                <td>
                  <span className={user.enabled ? "status-text enabled" : "status-text disabled"}>
                    <i aria-hidden="true" />{user.enabled ? "启用" : "禁用"}
                  </span>
                </td>
                <td>
                  {user.accessSummary?.platformAccess ? (
                    <strong className="access-scope platform">全部文档</strong>
                  ) : user.accessSummary ? (
                    <span className="access-scope">
                      公共 {user.accessSummary.publicDocuments} · 拥有 {user.accessSummary.ownedDocuments} · 授权 {user.accessSummary.explicitGrants} · 共 {user.accessSummary.totalDocuments}
                    </span>
                  ) : <span className="access-scope">等待统计</span>}
                </td>
                <td>{new Intl.DateTimeFormat("zh-CN", {
                  year: "numeric",
                  month: "2-digit",
                  day: "2-digit",
                  hour: "2-digit",
                  minute: "2-digit",
                }).format(new Date(user.createdAt))}</td>
                <td>
                  <div className="row-actions">
                    <button
                      type="button"
                      aria-label={`编辑 ${user.username}`}
                      onClick={() => setDrawer({ kind: "edit", user })}
                    >编辑</button>
                    <button
                      type="button"
                      aria-label={`重置 ${user.username} 的密码`}
                      onClick={() => setDrawer({ kind: "password", user })}
                    >重置密码</button>
                    <Link to={`/admin/users/${user.id}`}>文档权限</Link>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        {state === "loading" ? (
          <div className="table-state"><span className="spinner" /><p>正在加载用户</p></div>
        ) : null}
        {state === "error" ? (
          <div className="table-state error-state">
            <p>用户列表加载失败</p>
            <button className="secondary-button" type="button" onClick={() => loadUsers()}>重试</button>
          </div>
        ) : null}
        {state === "ready" && visibleUsers.length === 0 ? (
          <div className="table-state empty-state">
            <strong>没有匹配的用户</strong>
            <p>尝试更换搜索关键词</p>
          </div>
        ) : null}
      </div>

      {drawer ? (
        <UserDrawer
          key={drawer.kind === "create" ? "create" : `${drawer.kind}-${drawer.user.id}`}
          mode={drawer}
          onClose={() => setDrawer(null)}
          onSaved={handleSaved}
          onPasswordReset={(username) => setMessage(`已重置 ${username} 的密码，原登录会话已失效`)}
        />
      ) : null}
    </section>
  );
}
