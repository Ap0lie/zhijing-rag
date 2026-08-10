const ACTION_LABELS: Record<string, string> = {
  USER_CREATED: "创建用户",
  USER_SECURITY_CHANGED: "修改用户安全设置",
  USER_PASSWORD_RESET: "重置用户密码",
  DOCUMENT_GRANTS_CHANGED: "调整文档授权",
  DOCUMENT_ACL_CHANGED: "修改文档权限",
  RETRY: "重试任务",
  PARSER_OVERRIDE: "覆盖解析器",
  REPARSE: "重新解析",
  PUBLISH: "发布",
  ROLLBACK: "回滚",
  ENABLE: "启用",
  DISABLE: "停用",
  CANCEL: "取消",
};

export function auditActionLabel(action: string) {
  return ACTION_LABELS[action] ?? action.replaceAll("_", " ");
}
