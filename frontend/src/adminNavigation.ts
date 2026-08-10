export interface PageMeta {
  title: string;
  subtitle?: string;
}

export interface AdminPageLink extends PageMeta {
  href: string;
}

export interface AdminDomain extends PageMeta {
  key: "OVERVIEW" | "ACCESS_CONTENT" | "RETRIEVAL_KNOWLEDGE" | "QUALITY_OPERATIONS";
  href: string;
  pages: AdminPageLink[];
}

const PAGE_META: Record<string, PageMeta> = {
  "/": { title: "文档中心", subtitle: "管理原始文档、访问权限和不可变版本" },
  "/search": { title: "知识检索", subtitle: "在当前权限范围内检索已发布的 Child Chunk" },
  "/chat": { title: "可信问答", subtitle: "基于你有权查看的资料生成有出处、可核对的回答" },
  "/memory": { title: "长期记忆", subtitle: "查看、确认、替换、撤销与忘记只属于你的长期事实" },
  "/admin": { title: "管理总览", subtitle: "聚合需要处理的事项，正常状态下沉到对应模块" },
  "/admin/users": { title: "用户管理", subtitle: "创建账户并维护角色与访问状态" },
  "/admin/audit": { title: "操作日志", subtitle: "统一查看权限、Pipeline 与发布状态变化" },
  "/admin/pipeline": { title: "Pipeline 任务", subtitle: "检查处理状态、隔离原因和安全重试" },
  "/admin/retrieval": { title: "检索管理", subtitle: "管理模型、Embedding 缓存、索引代次与混合检索" },
  "/admin/graph": { title: "知识图谱", subtitle: "构建、发布并核对具有原文证据的知识图谱" },
  "/admin/evaluations": { title: "评测中心", subtitle: "评测质量、发布门禁、运行观测与本地故障演练" },
  "/admin/query-intelligence": { title: "查询智能", subtitle: "管理安全历史、问题改写、自动路由与共享预算" },
  "/forbidden": { title: "无权限" },
};

export const ADMIN_DOMAINS: AdminDomain[] = [
  {
    key: "OVERVIEW",
    title: "管理总览",
    subtitle: "只看需要处理的事项",
    href: "/admin",
    pages: [],
  },
  {
    key: "ACCESS_CONTENT",
    title: "访问与内容",
    subtitle: "用户、文档与处理任务",
    href: "/admin/users",
    pages: [
      { href: "/admin/users", title: "用户管理" },
      { href: "/admin/audit", title: "操作日志" },
      { href: "/admin/pipeline", title: "Pipeline 任务" },
      { href: "/", title: "文档中心" },
    ],
  },
  {
    key: "RETRIEVAL_KNOWLEDGE",
    title: "检索与知识",
    subtitle: "检索、图谱与查询策略",
    href: "/admin/retrieval",
    pages: [
      { href: "/admin/retrieval", title: "检索管理" },
      { href: "/admin/graph", title: "知识图谱" },
      { href: "/admin/query-intelligence", title: "查询智能" },
    ],
  },
  {
    key: "QUALITY_OPERATIONS",
    title: "质量与运维",
    subtitle: "评测、门禁与运行状态",
    href: "/admin/evaluations",
    pages: [{ href: "/admin/evaluations", title: "评测中心" }],
  },
];

export function getPageMeta(pathname: string): PageMeta {
  if (pathname.startsWith("/admin/users/")) {
    return { title: "用户详情", subtitle: "查看有效访问范围、明确授权与操作记录" };
  }
  if (pathname.startsWith("/documents/")) {
    return { title: "文档详情", subtitle: "查看元数据、权限和 Revision 时间线" };
  }
  if (pathname.startsWith("/chunks/")) {
    return { title: "Chunk 上下文", subtitle: "核对检索锚点、Parent 上下文和原文位置" };
  }
  return PAGE_META[pathname] ?? { title: "知境 RAG" };
}

export function getAdminDomain(pathname: string): AdminDomain | null {
  if (pathname === "/admin") return ADMIN_DOMAINS[0];
  if (!pathname.startsWith("/admin/")) return null;
  return ADMIN_DOMAINS.find((domain) => domain.pages.some((page) => (
    page.href === pathname || (page.href !== "/" && pathname.startsWith(`${page.href}/`))
  ))) ?? null;
}
