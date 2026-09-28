package ai.starlake.quack.edge.sql

import ai.starlake.sql.{SqlCommentStripper, SqlTrivia}

import java.util.Locale

/** DuckDB table functions that resolve their target from a STRING at run time: `query('<sql>')`,
  * `query_table('<name>')`, `json_execute_serialized_sql(...)` and the DuckLake extension's
  * `ducklake_*` family (`ducklake_table_insertions('<catalog>', '<schema>', '<table>', ...)`,
  * `ducklake_expire_snapshots('<catalog>')`, ...). The ACL parser only sees a table function call,
  * never the table it reaches, so neither catalog scoping nor the RLS / CLS rewriters can apply to
  * what it reads. Two consumers deny them for a tenant-scoped principal:
  *
  *   - [[PostgresAclValidator]], unconditionally: the `*.*.* ALL` wildcard covers "unsupported
  *     constructs", and admitting these under it lets a tenant admin read (or maintain) a sibling
  *     tenant's catalog that the wildcard's own catalog scoping refuses when named directly.
  *   - [[ai.starlake.quack.edge.policy.ProtectedWriteGuard]], for a principal with column or row
  *     policies, on every statement kind (including a plain SELECT, and with ACL disabled): reading
  *     a protected table through one of these returns unfiltered, unmasked rows.
  *
  * Lexical on purpose, not an AST walk: an unparseable statement reaches the wildcard-coverable
  * parse-error arm with no AST at all. The text is lowercased, stripped of comments and
  * trivia-normalized exactly as [[LockdownScreen]] does, then any call of a listed name (bare,
  * qualified or double-quoted, whitespace or a comment before the paren) matches. String literals
  * are not blanked, so a literal like `'query('` over-denies: the safe direction.
  */
object CatalogReachingCalls:

  private val Names = "query|query_table|json_execute_serialized_sql|ducklake_[a-z0-9_]*"

  private val Call =
    ("(?:\"(" + Names + ")\"|(?<![a-z0-9_$])(" + Names + "))\\s*\\(").r

  /** The first catalog-reaching function called by `sql`, lowercased, or None. */
  def find(sql: String): Option[String] =
    val lower = SqlTrivia.normalize(SqlCommentStripper.stripComments(sql.toLowerCase(Locale.ROOT)))
    Call.findFirstMatchIn(lower).map(m => Option(m.group(1)).getOrElse(m.group(2)))

  def denyReason(fn: String): String =
    s"table function $fn resolves its target at run time, so its access cannot be authorized; " +
      "not available to tenant-scoped principals (deny, fail-closed)"
