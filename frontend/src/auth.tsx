import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import { ApiError, apiRequest, loginRequest, logoutRequest, resetCsrfToken } from "./api";
import { clearSearchContinuityCache } from "./searchContinuityCache";
import type { CurrentUser } from "./types";

type AuthStatus = "loading" | "authenticated" | "anonymous";

interface AuthContextValue {
  status: AuthStatus;
  user: CurrentUser | null;
  notice: string | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  expireSession: () => void;
  clearNotice: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>("loading");
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    apiRequest<CurrentUser>("/api/v1/auth/me", { signal: controller.signal })
      .then((currentUser) => {
        setUser(currentUser);
        setStatus("authenticated");
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        setUser(null);
        setStatus("anonymous");
        if (error instanceof ApiError && error.status === 401) {
          resetCsrfToken();
        } else {
          setNotice("服务暂时不可用，请稍后重试");
        }
      });
    return () => controller.abort();
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const currentUser = await loginRequest(username, password);
    setUser(currentUser);
    setNotice(null);
    setStatus("authenticated");
  }, []);

  const logout = useCallback(async () => {
    let nextNotice = "已安全退出";
    try {
      await logoutRequest();
    } catch {
      nextNotice = "本地已退出，服务端会话清理失败";
    } finally {
      clearSearchContinuityCache();
      setUser(null);
      setNotice(nextNotice);
      setStatus("anonymous");
    }
  }, []);

  const expireSession = useCallback(() => {
    clearSearchContinuityCache();
    resetCsrfToken();
    setUser(null);
    setNotice("登录状态已失效，请重新登录");
    setStatus("anonymous");
  }, []);

  const clearNotice = useCallback(() => setNotice(null), []);

  const value = useMemo(
    () => ({ status, user, notice, login, logout, expireSession, clearNotice }),
    [status, user, notice, login, logout, expireSession, clearNotice],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return value;
}
