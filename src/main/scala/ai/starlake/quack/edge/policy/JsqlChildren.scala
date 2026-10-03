package ai.starlake.quack.edge.policy

/** Reflective child access over a jsqlparser AST, shared by the policy rewriters that must reach
  * EVERY node of a statement (mirrors TableExtractorVisitor.childAccessors, which is private to the
  * ACL parser package). A per-clause enumeration misses whatever it does not name (a clause type
  * added by a future jsqlparser included); walking every `get*` accessor that can return a child
  * node cannot.
  */
object JsqlChildren:

  private val accessorCache =
    new java.util.concurrent.ConcurrentHashMap[Class[?], Array[java.lang.reflect.Method]]()

  /** The no-arg `get*` accessors of `cls` that can reach a child AST node: return type is a
    * collection, an array, or any jsqlparser AST type outside the `net.sf.jsqlparser.parser`
    * package (excluded so the walk never follows an upward parent link into a cycle).
    */
  def accessors(cls: Class[?]): Array[java.lang.reflect.Method] =
    accessorCache.computeIfAbsent(
      cls,
      c =>
        c.getMethods.filter { m =>
          val rt = m.getReturnType
          m.getParameterCount == 0 &&
          m.getName.startsWith("get") &&
          (classOf[java.lang.Iterable[?]].isAssignableFrom(rt) || rt.isArray || isAstType(rt))
        }
    )

  def isAstType(rt: Class[?]): Boolean =
    val n = rt.getName
    n.startsWith("net.sf.jsqlparser.") && !n.startsWith("net.sf.jsqlparser.parser.")

  def isNode(value: AnyRef): Boolean = isAstType(value.getClass)

  /** The child nodes of `node`, collections and arrays flattened. An accessor that throws is
    * skipped (some getters fail on a node shape they do not apply to).
    */
  def of(node: AnyRef): List[AnyRef] =
    val out                     = List.newBuilder[AnyRef]
    def route(value: Any): Unit =
      value match
        case null                      => ()
        case it: java.lang.Iterable[?] => it.forEach(v => route(v))
        case arr: Array[?]             => arr.foreach(route)
        case n: AnyRef if isNode(n)    => out += n
        case _                         => ()
    accessors(node.getClass).foreach { m =>
      try route(m.invoke(node))
      catch case _: Throwable => ()
    }
    out.result()
