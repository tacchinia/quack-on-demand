package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.policy.JsqlChildren
import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import ai.starlake.transpiler.JSQLColumResolver
import ai.starlake.transpiler.schema.JdbcMetaData
import net.sf.jsqlparser.expression.{
  Alias,
  AnalyticExpression,
  AnyComparisonExpression,
  BinaryExpression,
  CaseExpression,
  CastExpression,
  CollateExpression,
  DoubleValue,
  Expression,
  ExtractExpression,
  Function,
  LongValue,
  NotExpression,
  NullValue,
  SignedExpression,
  StringValue,
  WindowDefinition
}
import net.sf.jsqlparser.expression.operators.relational.{
  Between,
  ExistsExpression,
  ExpressionList,
  InExpression,
  IsBooleanExpression,
  IsNullExpression,
  ParenthesedExpressionList
}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.select.{
  AllColumns,
  FromItem,
  Join,
  OrderByElement,
  ParenthesedFromItem,
  ParenthesedSelect,
  PlainSelect,
  Select,
  SelectItem,
  SetOperationList,
  WithItem
}

import scala.jdk.CollectionConverters._

final class JsqltranspilerRewriter extends SchemaAwareSqlRewriter:

  import JsqltranspilerRewriter.unquote
  import RewriteOutcome._

  private final case class DenyException(reason: String) extends RuntimeException(reason)

  def rewrite(
      sql: String,
      schema: Map[String, List[String]],
      policies: List[RoleColumnPolicy],
      defaultCatalog: Option[String],
      defaultSchema: Option[String],
      unresolvedMode: UnresolvedMode = UnresolvedMode.Deny
  ): RewriteOutcome =
    if policies.isEmpty then Passthrough
    else
      // Register the caller-provided tables under CURRENT_CATALOG/CURRENT_SCHEMA so SQL schema
      // qualifiers (e.g. `tpch1.customer`) match them when tpch1 IS the current schema; a table
      // with an empty column list is deliberately NOT registered (same as the old 3-arg
      // JSQLColumResolver constructor), which keeps "catalog knows nothing" -> unresolvedMode.
      // defaultCatalog/defaultSchema come from the caller's SchemaContext (the session-defaults
      // pinned at handshake time).
      val currentCatalog = defaultCatalog.getOrElse("")
      val currentSchema  = defaultSchema.getOrElse("")
      val metaData       = new JdbcMetaData(currentCatalog, currentSchema)
      schema.foreach { case (tableKey, cols) =>
        cols.foreach { col =>
          metaData.addTable(
            currentCatalog,
            currentSchema,
            tableKey,
            new ai.starlake.transpiler.schema.JdbcColumn(col)
          )
        }
      }
      // Seed DuckDB's fixed system-catalog shapes under their REAL schema names so metadata
      // queries (`information_schema.schemata`, `pg_catalog.pg_tables`, ...) resolve instead of
      // tripping the STRICT resolver into a fail-closed deny. They must be schema-qualified
      // entries: the flat `schema` map above lands under CURRENT_SCHEMA and can never match an
      // `information_schema.x` reference. Column policies never target system schemas, so these
      // seeds can only make a metadata query resolve, never unmask a user column; a system table
      // absent from SystemSchemaColumns stays unresolved and keeps failing closed.
      SystemSchemaColumns.all.foreach { case ((sysSchema, table), cols) =>
        cols.foreach { col =>
          metaData.addTable(
            currentCatalog,
            sysSchema,
            table,
            new ai.starlake.transpiler.schema.JdbcColumn(col)
          )
        }
      }
      val resolver = new JSQLColumResolver(metaData)
      resolver.setErrorMode(unresolvedMode match
        case UnresolvedMode.Deny => JdbcMetaData.ErrorMode.STRICT
        case UnresolvedMode.Pass => JdbcMetaData.ErrorMode.LENIENT)

      // Capture try/catch outcomes as Either so the early-exit flows through a structured
      // match instead of an exception. jsqltranspiler 1.9 does NOT ship a single
      // `JSQLDataException` umbrella; it raises one of the table/column/schema/catalog-not-
      // found exceptions under `ai.starlake.transpiler.*`.
      val resolveAttempt: Either[RewriteOutcome, String] =
        try Right(resolver.getResolvedStatementText(sql))
        catch
          case e: ai.starlake.transpiler.TableNotFoundException    => Left(Denied(e.getMessage))
          case e: ai.starlake.transpiler.TableNotDeclaredException => Left(Denied(e.getMessage))
          case e: ai.starlake.transpiler.ColumnNotFoundException   => Left(Denied(e.getMessage))
          case e: ai.starlake.transpiler.SchemaNotFoundException   => Left(Denied(e.getMessage))
          case e: ai.starlake.transpiler.CatalogNotFoundException  => Left(Denied(e.getMessage))
          case _: net.sf.jsqlparser.JSQLParserException            => Left(ParseFailed)
          case _: Throwable                                        => Left(ParseFailed)

      resolveAttempt match
        case Left(failure)       => failure
        case Right(resolvedText) =>
          val rsMeta                                          = resolver.getResultSetMetaData(sql)
          val projectionOrigins: IndexedSeq[(String, String)] =
            (1 to rsMeta.getColumnCount).toIndexedSeq.map { i =>
              (
                Option(rsMeta.getTableName(i)).getOrElse(""),
                Option(rsMeta.getColumnName(i)).getOrElse("")
              )
            }

          val parseAttempt: Either[RewriteOutcome, net.sf.jsqlparser.statement.Statement] =
            try Right(CCJSqlParserUtil.parse(resolvedText))
            catch case _: Throwable => Left(ParseFailed)

          parseAttempt match
            case Left(failure) => failure
            case Right(parsed) =>
              parsed match
                case sel: Select =>
                  // The resolver's STRICT mode only enforces table existence at the
                  // top level; a table referenced ONLY inside a subquery (IN / EXISTS /
                  // ANY / scalar) slips past it. Enforce the same fail-closed invariant
                  // at every nesting depth ourselves before any masking runs.
                  val nestedUnknown =
                    if unresolvedMode == UnresolvedMode.Deny then
                      firstUnknownTable(sql, schema, currentCatalog, currentSchema)
                    else None
                  nestedUnknown match
                    case Some(name) => Denied(s"unresolvable table $name")
                    case None       =>
                      try
                        val walk    = new Walk(policies, schema, cteColumns(sel))
                        val changed = walk.applyPolicies(sel, projectionOrigins)
                        // Fail closed on a covered column reference the masking walk did not
                        // reach: it would read the stored value (see refuseResidual for what
                        // this pass can and cannot see).
                        walk.refuseResidual(sel)
                        if changed then Rewritten(sel.toString) else Passthrough
                      catch case e: DenyException => Denied(e.reason)
                case _ =>
                  Passthrough

  /** First table reference, at ANY depth, that neither the caller-provided schema nor the
    * system-schema seeds know. CTE names are excluded by [[TablesNamesFinder]] itself. Matching is
    * deliberately the same name space the resolver was fed: a bare name must be a schema key; a
    * qualified name must be `[currentCatalog.]currentSchema.key` or a seeded system-schema table.
    * This check can only ADD denials on top of the resolver's own STRICT pass, never admit.
    */
  private def firstUnknownTable(
      originalSql: String,
      schema: Map[String, List[String]],
      currentCatalog: String,
      currentSchema: String
  ): Option[String] =
    val names =
      try
        // Scan the ORIGINAL text, not the resolved statement: the resolver
        // schema-qualifies CTE references (FROM x -> tpch1.x), which would defeat
        // the finder's own WITH-name exclusion and false-deny every CTE read.
        val parsed = CCJSqlParserUtil.parse(originalSql)
        val finder = new net.sf.jsqlparser.util.TablesNamesFinder()
        val list   = finder.getTableList(parsed)
        list.asScala.toList
      catch
        case _: Throwable => Nil
    // Known = the caller's catalog produced a NON-EMPTY column list for the name (an
    // unknown table lands in the schema map with Nil, which the resolver also refuses
    // to register), or the name is a seeded system-schema table.
    def knownUserTable(name: String): Boolean =
      schema.exists((k, cols) => k.equalsIgnoreCase(name) && cols.nonEmpty)
    def known(raw: String): Boolean =
      raw.split('.').toList.map(unquote).reverse match
        case Nil                => true
        case name :: Nil        => knownUserTable(name)
        case name :: q1 :: rest =>
          val sysKnown = SystemSchemaColumns.all.keys.exists { case (s, t) =>
            s.equalsIgnoreCase(q1) && t.equalsIgnoreCase(name)
          }
          val qualifierOk = rest match
            case Nil      => true
            case c :: Nil => c.equalsIgnoreCase(currentCatalog)
            case _        => false
          val userKnown = q1.equalsIgnoreCase(currentSchema) && knownUserTable(name) && qualifierOk
          sysKnown || userKnown
    names.find(n => !known(n))

  /** One FROM item of a select: the name a qualifier reaches it by (its alias, else its table name;
    * empty for an unaliased derived table), the table (or CTE / derived-table name) it reads, and
    * its column names when known. Every name is unquoted.
    */
  private final case class FromEntry(key: String, table: String, columns: Option[List[String]])

  /** Which policy, if any, a column reference reads through. */
  private enum Binding:
    case Covered(policy: RoleColumnPolicy, table: String)
    case Clear

    /** More than one FROM item could own the reference and at least one of them is covered: the
      * mask cannot be placed with certainty, so the statement is refused.
      */
    case Ambiguous

  /** Exposed column names of every top-level CTE, by unquoted name (None when not knowable, e.g. a
    * `SELECT *` body), so an unqualified reference through a CTE resolves to it.
    */
  private def cteColumns(sel: Select): Map[String, Option[List[String]]] =
    Option(sel.getWithItemsList)
      .map(_.asScala.toList)
      .getOrElse(Nil)
      .flatMap { wi =>
        Option(wi.getAlias).map { alias =>
          val listed = Option(wi.getWithItemList).map(_.asScala.toList).getOrElse(Nil)
          val cols   =
            if listed.nonEmpty then Some(listed.map(si => unquote(si.getExpression.toString)))
            else Option(wi.getSelect).flatMap(ps => exposedColumns(ps.getSelect))
          unquote(alias.getName) -> cols
        }
      }
      .toMap

  /** The output column names of a select, None when it projects a star. */
  private def exposedColumns(sel: Select): Option[List[String]] =
    sel match
      case ps: PlainSelect =>
        val items = Option(ps.getSelectItems).map(_.asScala.toList).getOrElse(Nil)
        if items.exists(_.getExpression.isInstanceOf[AllColumns]) then None
        else
          Some(items.map { si =>
            Option(si.getAlias).map(a => unquote(a.getName)).getOrElse {
              si.getExpression match
                case c: Column => unquote(c.getColumnName)
                case e         => e.toString
            }
          })
      case sol: SetOperationList =>
        Option(sol.getSelects).flatMap(_.asScala.headOption).flatMap(exposedColumns)
      case p: ParenthesedSelect => exposedColumns(p.getSelect)
      case _                    => None

  /** The masking walk over one statement. Every column reference is bound to the FROM item that
    * owns it by UNQUOTED, case-insensitive names, which is how DuckDB binds identifiers, quoted or
    * not: jsqlparser keeps the double quotes of `"c_email"` / `"main"."customer"`, and comparing
    * those raw names against a policy's `c_email` / `customer` is what let a quoted reference read
    * the stored value in WHERE / ORDER BY / GROUP BY.
    */
  private final class Walk(
      policies: List[RoleColumnPolicy],
      schema: Map[String, List[String]],
      ctes: Map[String, Option[List[String]]]
  ):
    /** Column nodes a mask transform brought in: they read the stored value by design. */
    private val transformColumns: java.util.Set[AnyRef] =
      java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
      )

    private def columnsOf(table: String): Option[List[String]] =
      schema.collectFirst { case (k, cols) if k.equalsIgnoreCase(table) && cols.nonEmpty => cols }

    /** FROM + JOIN items of a select. A parenthesized join contributes its members (under its alias
      * when it has one); a derived table its exposed columns; anything else (a table function,
      * VALUES) an entry with unknown columns, which can own any unqualified name.
      */
    def fromEntries(ps: PlainSelect): List[FromEntry] =
      itemsOf(ps.getFromItem, ps.getJoins).flatMap(entriesOf)

    private def itemsOf(
        first: FromItem,
        joins: java.util.List[Join]
    ): List[FromItem] =
      Option(first).toList ::: Option(joins)
        .map(_.asScala.toList.flatMap(j => Option(j.getFromItem)))
        .getOrElse(Nil)

    private def aliasOf(item: FromItem): Option[String] =
      Option(item.getAlias).map(a => unquote(a.getName))

    private def entriesOf(item: FromItem): List[FromEntry] =
      item match
        case t: Table =>
          val raw  = unquote(t.getName)
          val cols =
            ctes.collectFirst {
              case (name, c) if t.getSchemaName == null && name.equalsIgnoreCase(raw) => c
            } match
              case Some(c) => c
              case None    => columnsOf(raw)
          List(FromEntry(aliasOf(t).getOrElse(raw), raw, cols))
        case s: ParenthesedSelect =>
          val key = aliasOf(s).getOrElse("")
          List(FromEntry(key, key, exposedColumns(s.getSelect)))
        case p: ParenthesedFromItem =>
          val inner = itemsOf(p.getFromItem, p.getJoins).flatMap(entriesOf)
          aliasOf(p).fold(inner)(a => inner.map(_.copy(key = a)))
        case other =>
          val key = aliasOf(other).getOrElse("")
          List(FromEntry(key, key, None))

    /** Bind `col` to the policy it reads through. A qualifier names a FROM item of the current
      * select, else of an enclosing one (a correlated reference); one naming no FROM item is taken
      * as the table name itself (`main.customer.c_email`). An unqualified name belongs to the FROM
      * items that have such a column (or whose columns are unknown), innermost select first.
      */
    def bind(
        col: Column,
        local: List[FromEntry],
        outer: List[FromEntry],
        extra: List[RoleColumnPolicy]
    ): Binding =
      val name      = unquote(col.getColumnName)
      val qualifier =
        Option(col.getTable).flatMap(t => Option(t.getName)).map(unquote).filter(_.nonEmpty)
      val tables = qualifier match
        case Some(q) =>
          local.filter(_.key.equalsIgnoreCase(q)) match
            case Nil =>
              outer.filter(_.key.equalsIgnoreCase(q)) match
                case Nil  => List(q)
                case hits => hits.map(_.table)
            case hits => hits.map(_.table)
        case None =>
          def owners(scope: List[FromEntry]) =
            scope.filter(_.columns.forall(_.exists(_.equalsIgnoreCase(name))))
          (owners(local) match
            case Nil  => owners(outer)
            case hits => hits
          ).map(_.table)
      val all     = extra ::: policies
      val covered = tables.flatMap(t => matchingPolicy(t, name, all).map(_ -> t))
      covered match
        case Nil                             => Binding.Clear
        case (p, t) :: _ if tables.size == 1 => Binding.Covered(p, t)
        case _                               => Binding.Ambiguous

    /** The replacement for a covered column: the policy's transform, parenthesized when it lands
      * inside an expression so an operator around it cannot regroup it (`x::T` after `a || b`). A
      * whole projection item keeps the bare transform, as before.
      */
    def maskFor(p: RoleColumnPolicy, nested: Boolean): Expression =
      val e = CCJSqlParserUtil.parseExpression(p.transformSql.get)
      markTransform(e)
      e match
        case _: Column | _: Function | _: StringValue | _: LongValue | _: DoubleValue |
            _: NullValue | _: CaseExpression | _: ParenthesedExpressionList[?] =>
          e
        case _ if !nested => e
        case _            => new ParenthesedExpressionList[Expression](e)

    private def markTransform(node: AnyRef): Unit =
      node match
        case c: Column => transformColumns.add(c): Unit
        case other     => JsqlChildren.of(other).foreach(markTransform)

    /** Walk the resolved statement and replace every Column whose physical (table, column) lineage
      * matches a policy, in the projection, WHERE, HAVING, GROUP BY, ORDER BY, JOIN ON, QUALIFY,
      * DISTINCT ON and named WINDOW clauses and through every nested select.
      *
      * `outerScope` threads the ENCLOSING queries' FROM items into subquery descent, so a
      * correlated reference through an outer alias (`EXISTS (... WHERE c.c_phone = ...)`), or an
      * unqualified one the inner FROM items do not own, resolves to its base table and its policy
      * applies. Local FROM items shadow outer ones.
      */
    def applyPolicies(
        sel: Select,
        origins: IndexedSeq[(String, String)],
        outerScope: List[FromEntry] = Nil
    ): Boolean =
      val changed = new java.util.concurrent.atomic.AtomicBoolean(false)
      val visitor = new PolicyVisitor(changed)
      def recurse(inner: Select, scope: List[FromEntry]): Unit =
        if applyPolicies(inner, IndexedSeq.empty, scope) then changed.set(true)

      // Recurse into CTE bodies first. Top-level lineage doesn't apply inside the CTE body, so
      // pass empty origins; the visitor binds each Column through the body's own FROM clause.
      Option(sel.getWithItemsList).foreach(_.forEach { wi =>
        Option(wi.getSelect).foreach(ps => recurse(ps.getSelect, outerScope))
      })

      sel match
        case sol: SetOperationList =>
          // UNION / INTERSECT / EXCEPT: recurse into every arm. Each arm has its own FROM clause
          // and its own per-column policy lookup; outer-query origins don't apply, but a set
          // operation nested in a correlated subquery still sees the enclosing scope.
          Option(sol.getSelects).foreach(_.forEach(arm => recurse(arm, outerScope)))
        case wrap: ParenthesedSelect =>
          // Top-level parenthesized SELECT: unwrap and recurse.
          recurse(wrap.getSelect, outerScope)
        case ps: PlainSelect =>
          visitor.fromTables = fromEntries(ps)
          visitor.outerTables = outerScope
          if Option(ps.getJoins).exists(_.asScala.exists(_.isNatural)) &&
            visitor.fromTables.exists(e => policies.exists(p => tableMatches(p, e.table)))
          then throw DenyException("a NATURAL join over a table with a column policy")
          // The scope a nested subquery of THIS select sees: local FROM items first
          // (they shadow), then whatever this select itself inherited.
          val childScope = visitor.fromTables ::: outerScope
          // FROM-item subquery: recurse so policies apply inside `FROM (SELECT ... FROM
          // customer)`. Before recursion (which would mutate inner Columns into transform
          // literals) we snapshot the inner SELECT's exposed covered columns and synthesize
          // transient policies so the outer projection masks `sub.c_email` too. The resolver's
          // ResultSet lineage stops at the FROM boundary in jsqltranspiler 1.9, so we trace it
          // ourselves. Derived tables see the outer scope too: only legal for LATERAL, but
          // over-masking an illegal reference is harmless (the engine rejects it).
          val derivedPolicies = scala.collection.mutable.ListBuffer.empty[RoleColumnPolicy]
          itemsOf(ps.getFromItem, ps.getJoins).foreach {
            case sub: ParenthesedSelect =>
              derivedPolicies ++= deriveOuterPolicies(sub)
              recurse(sub.getSelect, childScope)
            case _ => ()
          }
          // The synthesized policies use the FROM-item alias as the tableName, so binding a
          // `sub.x` reference (or an unqualified one `sub` owns) matches them.
          if derivedPolicies.nonEmpty then visitor.extraPolicies = derivedPolicies.toList
          val items = ps.getSelectItems
          if items != null then
            val it  = items.listIterator()
            var idx = 0
            while it.hasNext do
              val si = it.next()
              // Top-level column origin override: the resolver knows the physical lineage of
              // the projection slot even if the expression at that slot is itself a Column
              // whose name is an alias of another column. Only meaningful when origins are
              // available (top-level call); inside recursion (e.g. CTE body) origins is empty
              // and the visitor binds each Column through its own qualifier.
              visitor.topLevelOverride =
                if origins.indices.contains(idx) then Some(origins(idx)) else None
              val expr     = si.getExpression
              val replaced = visitor.visit(expr, nested = false)
              if replaced ne expr then
                val item = si.asInstanceOf[SelectItem[Expression]]
                item.setExpression(replaced)
                // A bare `SELECT c_phone` (no explicit AS) relies on the projected Column's own
                // name for the result-set's field name. Masking replaces that Column with a
                // value expression (e.g. the literal `'***'`), which has no name of its own --
                // DuckDB would otherwise synthesize one from the expression text, changing the
                // column name a client sees out from under it. Preserve the original name as an
                // explicit alias so masking is transparent to the caller. Only for a directly-
                // masked top-level Column: an explicit user alias (`SELECT c_phone AS foo`) is
                // left alone, and a masked column nested inside a function/CASE/etc. keeps
                // whatever name that enclosing expression gets.
                if item.getAlias == null then
                  expr match
                    case col: Column => item.setAlias(new Alias(col.getColumnName))
                    case _           => ()
                changed.set(true)
              visitor.topLevelOverride = None
              idx += 1
          Option(ps.getDistinct)
            .flatMap(d => Option(d.getOnSelectItems))
            .foreach(_.forEach { si =>
              val expr = si.getExpression
              val nxt  = visitor.visit(expr)
              if nxt ne expr then
                si.asInstanceOf[SelectItem[Expression]].setExpression(nxt)
                changed.set(true)
            })
          Option(ps.getJoins).foreach(_.forEach { j =>
            Option(j.getOnExpressions).filterNot(_.isEmpty).foreach { ons =>
              val before = ons.asScala.toList
              val after  = before.map(visitor.visit(_))
              if before.lazyZip(after).exists(_ ne _) then
                j.setOnExpressions(new java.util.ArrayList(after.asJava))
                changed.set(true)
            }
          })
          Option(ps.getWhere).foreach { w =>
            val nxt = visitor.visit(w)
            if nxt ne w then { ps.setWhere(nxt); changed.set(true) }
          }
          Option(ps.getHaving).foreach { h =>
            val nxt = visitor.visit(h)
            if nxt ne h then { ps.setHaving(nxt); changed.set(true) }
          }
          Option(ps.getQualify).foreach { q =>
            val nxt = visitor.visit(q)
            if nxt ne q then { ps.setQualify(nxt); changed.set(true) }
          }
          Option(ps.getGroupBy).foreach { gb =>
            val gbList = gb.getGroupByExpressionList
            if gbList != null then
              val it = gbList.listIterator()
              while it.hasNext do
                val cur = it.next()
                val nxt = visitor.visit(cur)
                if nxt ne cur then { it.set(nxt); changed.set(true) }
          }
          Option(ps.getWindowDefinitions).foreach(_.forEach(visitor.visitWindow))
          visitor.visitOrderBy(ps.getOrderByElements)
        case _ => ()
      changed.get

    /** Pre-scan a FROM-item subquery and synthesize policies for the outer scope. For each inner
      * SelectItem whose source column is covered by a base-table policy, emit a transient policy
      * keyed on `(subqueryAlias, projectedName)` so the outer projection masks `sub.x` references.
      * The projectedName is the user-supplied alias if any, else the inner Column's name. Items
      * that are not bare Columns (functions, expressions) are skipped - those don't expose a
      * cleanly maskable identity to the outer scope.
      */
    private def deriveOuterPolicies(sub: ParenthesedSelect): List[RoleColumnPolicy] =
      val subAlias = Option(sub.getAlias).map(a => unquote(a.getName)).getOrElse("")
      if subAlias.isEmpty then Nil
      else
        sub.getSelect match
          case ips: PlainSelect =>
            val innerFrom = fromEntries(ips)
            Option(ips.getSelectItems).map(_.asScala.toList).getOrElse(Nil).flatMap { si =>
              si.getExpression match
                case col: Column =>
                  bind(col, innerFrom, Nil, Nil) match
                    case Binding.Covered(p, _) =>
                      val exposed = Option(si.getAlias)
                        .map(a => unquote(a.getName))
                        .getOrElse(unquote(col.getColumnName))
                      List(p.copy(tableName = subAlias, columnName = exposed))
                    case _ => Nil
                case _ => Nil
            }
          case _ => Nil

    /** Refuse the statement when a column reference that binds to a covered column is still in the
      * tree after the masking walk: a clause or expression shape the walk does not rewrite (a USING
      * list, a function's keyword argument, an expression type it does not descend) would otherwise
      * read the stored value. Walks every node reflectively, so a column reference is found
      * whichever clause holds it. Columns a mask transform introduced are exempt, as are names that
      * declare rather than read a column (a star's EXCLUDE list, a CTE's column list).
      *
      * It sees column references by name only. A covered value reached without naming the covered
      * column is outside it: a column renamed by a table alias's column list (`AS c(i, e)`), a
      * whole-row reference to a table alias (`c::VARCHAR`) and method-call syntax on a column
      * (`c_email.substr(1, 3)`) are not refused here.
      */
    def refuseResidual(root: AnyRef): Unit =
      val seen: java.util.Set[AnyRef] =
        java.util.Collections.newSetFromMap(
          new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
        )
      def go(node: AnyRef, local: List[FromEntry], outer: List[FromEntry]): Unit =
        if node != null && seen.add(node) then
          node match
            case col: Column =>
              if !transformColumns.contains(col) then
                bind(col, local, outer, Nil) match
                  case Binding.Clear => ()
                  case _             =>
                    throw DenyException(
                      s"column ${unquote(col.getColumnName)} is referenced where its column " +
                        "policy cannot be applied"
                    )
            case ac: AllColumns =>
              Option(ac.getReplaceExpressions).foreach(_.forEach(go(_, local, outer)))
            case wi: WithItem[?] =>
              // `WITH x(c_email) AS (...)` declares output names; only the body reads.
              Option(wi.getSelect).foreach(go(_, local, outer))
            case ps: PlainSelect =>
              val inner = fromEntries(ps)
              JsqlChildren.of(ps).foreach(go(_, inner, local ::: outer))
            case other => JsqlChildren.of(other).foreach(go(_, local, outer))
      go(root, Nil, Nil)

    private final class PolicyVisitor(changed: java.util.concurrent.atomic.AtomicBoolean):
      var topLevelOverride: Option[(String, String)] = None
      var fromTables: List[FromEntry]                = Nil
      // Enclosing queries' FROM items, threaded through subquery descent so correlated
      // references resolve; local fromTables always shadow these.
      var outerTables: List[FromEntry]          = Nil
      var extraPolicies: List[RoleColumnPolicy] = Nil

      private def subquery(sel: Select): Unit =
        if applyPolicies(sel, IndexedSeq.empty, fromTables ::: outerTables) then changed.set(true)

      /** Visit each element of `list` in place. */
      private def visitList(list: java.util.List[Expression]): Unit =
        if list != null then
          val it = list.listIterator()
          while it.hasNext do
            val cur = it.next()
            val nxt = visit(cur)
            if nxt ne cur then it.set(nxt)

      def visitOrderBy(obs: java.util.List[OrderByElement]): Unit =
        Option(obs).foreach(_.forEach { ob =>
          val nxt = visit(ob.getExpression)
          if nxt ne ob.getExpression then { ob.setExpression(nxt); changed.set(true) }
        })

      /** A named or inline window: its PARTITION BY and ORDER BY read the column. */
      def visitWindow(wd: WindowDefinition): Unit =
        if wd != null then
          Option(wd.getPartitionExpressionList).foreach { lst =>
            visitList(lst.asInstanceOf[java.util.List[Expression]])
          }
          visitOrderBy(wd.getOrderByElements)

      /** Walk `expr` and return its replacement (same instance if nothing changed). `nested` is
        * false only for a whole projection item.
        */
      // Parenthesis is deprecated in jsqlparser 5.x (ParenthesedExpressionList is the
      // replacement, matched below) but the class still exists and dropping the case
      // would silently skip rewriting any subtree the parser still wraps in it.
      @scala.annotation.nowarn("msg=class Parenthesis")
      def visit(expr: Expression, nested: Boolean = true): Expression =
        val saved = topLevelOverride
        // The origin override applies to a projection item's root Column only.
        if !expr.isInstanceOf[Column] then topLevelOverride = None
        try
          expr match
            case col: Column =>
              val byOrigin = topLevelOverride.flatMap { (t, c) =>
                matchingPolicy(t, c, extraPolicies ::: policies).map(Binding.Covered(_, t))
              }
              byOrigin.getOrElse(bind(col, fromTables, outerTables, extraPolicies)) match
                case Binding.Covered(p, table) if p.action == RoleColumnPolicy.ActionDeny =>
                  throw DenyException(s"column $table.${unquote(col.getColumnName)} is denied")
                case Binding.Covered(p, _) =>
                  changed.set(true)
                  maskFor(p, nested)
                case Binding.Ambiguous =>
                  throw DenyException(
                    s"column ${unquote(col.getColumnName)} could belong to more than one FROM " +
                      "item, one of them under a column policy; qualify it"
                  )
                case Binding.Clear => col

            case fn: Function =>
              Option(fn.getParameters)
                .foreach(p => visitList(p.asInstanceOf[java.util.List[Expression]]))
              Option(fn.getNamedParameters)
                .foreach(p => visitList(p.asInstanceOf[java.util.List[Expression]]))
              visitOrderBy(fn.getOrderByElements)
              fn

            case b: BinaryExpression =>
              b.setLeftExpression(visit(b.getLeftExpression))
              b.setRightExpression(visit(b.getRightExpression))
              b

            case p: net.sf.jsqlparser.expression.Parenthesis =>
              p.setExpression(visit(p.getExpression))
              p

            case el: ParenthesedExpressionList[Expression] @unchecked =>
              visitList(el)
              el

            case ae: AnalyticExpression =>
              Option(ae.getExpression).foreach(e => ae.setExpression(visit(e)))
              Option(ae.getPartitionExpressionList)
                .foreach(lst => visitList(lst.asInstanceOf[java.util.List[Expression]]))
              visitOrderBy(ae.getOrderByElements)
              visitOrderBy(ae.getFuncOrderBy)
              Option(ae.getFilterExpression).foreach(e => ae.setFilterExpression(visit(e)))
              Option(ae.getOffset).foreach(e => ae.setOffset(visit(e)))
              Option(ae.getDefaultValue).foreach(e => ae.setDefaultValue(visit(e)))
              visitWindow(ae.getWindowDefinition)
              ae

            case ex: ExtractExpression =>
              ex.setExpression(visit(ex.getExpression))
              ex

            case bt: Between =>
              bt.setLeftExpression(visit(bt.getLeftExpression))
              bt.setBetweenExpressionStart(visit(bt.getBetweenExpressionStart))
              bt.setBetweenExpressionEnd(visit(bt.getBetweenExpressionEnd))
              bt

            case ix: InExpression =>
              ix.setLeftExpression(visit(ix.getLeftExpression))
              ix.getRightExpression match
                case el: ExpressionList[Expression] @unchecked => visitList(el)
                case other                                     =>
                  // e.g. `IN (SELECT c_phone FROM customer)`: the right side is a
                  // ParenthesedSelect, which visit() recurses into so a covered column inside the
                  // subquery is masked (otherwise the IN filter would act as a membership oracle
                  // on the true values).
                  val nxt = visit(other)
                  if nxt ne other then ix.setRightExpression(nxt)
              ix

            case nl: IsNullExpression =>
              nl.setLeftExpression(visit(nl.getLeftExpression))
              nl

            case ib: IsBooleanExpression =>
              ib.setLeftExpression(visit(ib.getLeftExpression))
              ib

            case se: SignedExpression =>
              se.setExpression(visit(se.getExpression))
              se

            case co: CollateExpression =>
              co.setLeftExpression(visit(co.getLeftExpression))
              co

            case cx: CastExpression =>
              cx.setLeftExpression(visit(cx.getLeftExpression))
              cx

            case ce: CaseExpression =>
              Option(ce.getSwitchExpression).foreach(sw => ce.setSwitchExpression(visit(sw)))
              Option(ce.getWhenClauses).foreach(_.forEach { wc =>
                wc.setWhenExpression(visit(wc.getWhenExpression))
                wc.setThenExpression(visit(wc.getThenExpression))
              })
              Option(ce.getElseExpression).foreach(el => ce.setElseExpression(visit(el)))
              ce

            case ne: NotExpression =>
              // A leading NOT parses as a NotExpression wrapper (NOT ExistsExpression.isNot), so
              // `NOT EXISTS (...)` and `NOT (x = ANY(...))` arrive here. Descend into the
              // wrapped predicate like the other unary wrappers, otherwise the negated form
              // leaks the covered column unmasked. (`x NOT IN (...)` stays an InExpression with
              // isNot=true and rides the InExpression case above.)
              ne.setExpression(visit(ne.getExpression))
              ne

            case ex: ExistsExpression =>
              // `EXISTS (SELECT ...)`: the wrapped select must be descended exactly like the
              // InExpression right side, otherwise a covered column inside the subquery is
              // forwarded unmasked and EXISTS acts as a membership oracle on the true values.
              ex.setRightExpression(visit(ex.getRightExpression))
              ex

            case ac: AnyComparisonExpression =>
              // `x = ANY(SELECT ...)` / `= ALL` / `= SOME`: descend into the quantified subquery
              // so covered columns inside it are masked; otherwise the comparison is a
              // true/false oracle on the masked values. The select field is final (no setter),
              // so it is mutated in place.
              Option(ac.getSelect).foreach(subquery)
              ac

            case ps: ParenthesedSelect =>
              // Scalar subquery in expression position, e.g. `SELECT (SELECT c_email FROM
              // customer)`. The inner FROM clause and per-column policy lookup belong to the
              // subquery itself, so the outer origins don't apply.
              subquery(ps.getSelect)
              ps

            // Anything else is left as is; refuseResidual fails the statement closed if a
            // column reference in it binds to a covered column.
            case other => other
        finally topLevelOverride = saved

  private def tableMatches(p: RoleColumnPolicy, table: String): Boolean =
    p.tableName == RoleColumnPolicy.Wildcard || p.tableName.equalsIgnoreCase(table)

  private def matchingPolicy(
      table: String,
      column: String,
      policies: List[RoleColumnPolicy]
  ): Option[RoleColumnPolicy] =
    policies.find(p => tableMatches(p, table) && p.columnName.equalsIgnoreCase(column))

object JsqltranspilerRewriter:

  /** An identifier as DuckDB binds it: jsqlparser keeps the double quotes of a quoted identifier
    * (`"c_email"`, with `""` for an embedded quote), while DuckDB matches identifiers
    * case-insensitively whether quoted or not. Every policy match compares names unquoted, with
    * `equalsIgnoreCase`.
    */
  private[cls] def unquote(s: String): String =
    if s != null && s.length >= 2 && s.startsWith("\"") && s.endsWith("\"") then
      s.substring(1, s.length - 1).replace("\"\"", "\"")
    else s
