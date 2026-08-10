import { useState, type FormEvent } from "react";
import { Navigate, useNavigate } from "react-router-dom";

import { ApiError } from "../api";
import { useAuth } from "../auth";
import { DocumentIcon, EyeIcon, ShieldIcon } from "../components/Icons";

export function LoginPage() {
  const { status, login, notice, clearNotice } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (status === "authenticated") {
    return <Navigate to="/" replace />;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (status !== "anonymous") {
      return;
    }
    clearNotice();
    setError(null);
    setSubmitting(true);
    try {
      await login(username, password);
      navigate("/", { replace: true });
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "登录失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login-layout">
      <section className="login-brand" aria-label="知境 RAG">
        <div className="login-brand-content">
          <DocumentIcon className="login-mark" />
          <h1>
            知境 <span>RAG</span>
          </h1>
          <p>让每个答案都有出处</p>
          <div className="citation-motif" aria-hidden="true">
            <div><b>001</b><strong># 来源文档</strong></div>
            <div><b>002</b><span>产品需求文档.md</span><em>p.12</em></div>
            <div><b>003</b><span>架构设计说明.md</span><em>p.27</em></div>
            <div className="motif-gap"><b>004</b><strong># 引用片段</strong></div>
            <div><b>005</b><span>回答必须提供可追溯依据</span><em>p.12</em></div>
            <div><b>006</b><span>权限边界在检索前执行</span><em>p.8</em></div>
          </div>
        </div>
      </section>

      <section className="login-form-area">
        <form className="login-form" onSubmit={handleSubmit} noValidate>
          <h2>登录知识工作台</h2>
          <p className="form-intro">使用本地账户继续</p>

          {notice ? <div className="notice-banner" role="status">{notice}</div> : null}

          <label htmlFor="username">用户名</label>
          <input
            id="username"
            name="username"
            autoComplete="username"
            autoFocus
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            aria-invalid={Boolean(error)}
            required
          />

          <label htmlFor="password">密码</label>
          <div className="password-field">
            <input
              id="password"
              name="password"
              type={showPassword ? "text" : "password"}
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              aria-invalid={Boolean(error)}
              required
            />
            <button
              type="button"
              aria-label={showPassword ? "隐藏密码" : "显示密码"}
              onClick={() => setShowPassword((visible) => !visible)}
            >
              <EyeIcon crossed={showPassword} />
            </button>
          </div>

          {error ? <p className="form-error" role="alert">{error}</p> : null}

          <button
            className="primary-button login-submit"
            type="submit"
            disabled={status !== "anonymous" || submitting || !username.trim() || !password}
          >
            {submitting ? "正在登录" : "登录"}
          </button>
          <div className="security-note">
            <ShieldIcon />
            <span>服务端会话 · 安全 Cookie</span>
          </div>
        </form>
      </section>
    </main>
  );
}
