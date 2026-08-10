export function ForbiddenPage() {
  return (
    <section className="forbidden-page">
      <span className="error-code">403</span>
      <h2>没有访问权限</h2>
      <p>当前账户不能访问此管理页面。权限由服务端最终判定。</p>
      <a href="/">返回工作台</a>
    </section>
  );
}
