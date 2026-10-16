import { Parser } from 'node-sql-parser/build/mysql'

export interface TenantTableCheck {
  name: string
  alias: string
  from: number
  to: number
  covered: boolean
}
export interface TenantSqlCheck {
  tables: TenantTableCheck[]
  parsed: boolean
}
// Analysis only. Never rewrite the SQL that is executed or use this as authorization.
const parser = new Parser()
const identifier = String.raw`(?:\x60(?:\x60\x60|[^\x60])+\x60|"(?:""|[^"])+"|\[[^\]]+\]|[\p{L}_][\p{L}\p{N}_$]*)`
const normalize = (s: string) =>
  String(s || '')
    .replace(/^[`"\[]|[`"\]]$/g, '')
    .toLowerCase()

function prepare(source: string) {
  // Keep every offset stable, including strings/comments containing placeholder-like text.
  return source.replace(
    /'(?:''|\\.|[^'\\])*'|"(?:""|[^"])*"|`(?:``|[^`])*`|--[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/|\$(?:DE_PARAM\{[^}]+\}|f2cde\[[^\]]+\]|\{[^}]+\})|\bAS\s+STRING(?=\s*\))/gi,
    token => {
      if (token.startsWith('$')) return `'${'p'.repeat(token.length - 2)}'`
      if (/^AS\s+STRING$/i.test(token)) return token.replace(/STRING$/i, 'CHAR  ')
      return token
    }
  )
}

export function checkTenantSql(source: string): TenantSqlCheck {
  const tables: TenantTableCheck[] = []
  if (!source.trim()) return { tables, parsed: true }
  try {
    if (source.length > 100000) throw new Error('SQL too large for interactive analysis')
    const ast = parser.astify(prepare(source), { parseOptions: { includeLocations: true } })
    // The parser's dialect-specific expression nodes have heterogeneous shapes.
    const visit = (
      query: any,
      outer = new Map<string, boolean>(),
      inheritedCtes = new Map<string, boolean>()
    ): boolean => {
      if (!query || query.type !== 'select') throw new Error('Only SELECT is supported')
      const ctes = new Map(inheritedCtes)
      for (const cte of query.with || []) {
        const name = normalize(cte.name?.value)
        ctes.set(name, false) // Recursive self-reference must not prove itself safe.
        ctes.set(name, visit(cte.stmt?.ast || cte.stmt, new Map(), ctes))
      }
      const refs = query.from || []
      const local = new Map<string, boolean>()
      const rows = new Map<string, TenantTableCheck>()
      for (const ref of refs) {
        const name = normalize(ref.as || ref.table)
        if (!name || local.has(name)) throw new Error('Ambiguous table alias')
        const child = ref.expr?.ast
        local.set(
          name,
          child
            ? visit(child, new Map(), ctes)
            : (!ref.db && ctes.get(normalize(ref.table))) || false
        )
        if (ref.table && ref.loc) {
          const from = ref.loc.start.offset
          const match = source
            .slice(from, ref.loc.end.offset)
            .match(new RegExp(`^${identifier}(?:\\s*\\.\\s*${identifier})*`, 'u'))
          if (!match) throw new Error('Missing table position')
          const row = {
            name: match[0],
            alias: ref.as || ref.table,
            from,
            to: from + match[0].length,
            covered: false
          }
          tables.push(row)
          rows.set(name, row)
        }
      }
      const column = (expr: any): string | undefined => {
        if (expr?.type !== 'column_ref' || normalize(expr.column) !== 'tenant_id') return
        if (expr.table) return normalize(expr.table)
        if (local.size === 1) return [...local.keys()][0]
      }
      const literal = (expr: any) =>
        ['single_quote_string', 'number', 'param', 'var'].includes(expr?.type) &&
        expr.value !== null
      const known = (name: string, covered: Set<string>) =>
        local.has(name) ? covered.has(name) : outer.get(name) === true
      const prove = (expr: any, covered: Set<string>): Set<string> => {
        if (!expr || expr.type !== 'binary_expr') return new Set()
        const op = expr.operator.toUpperCase()
        if (op === 'AND' || op === 'OR') {
          const left = prove(expr.left, covered),
            right = prove(expr.right, covered)
          return op === 'AND'
            ? new Set([...left, ...right])
            : new Set([...left].filter(x => right.has(x)))
        }
        const left = column(expr.left),
          right = column(expr.right)
        const result = new Set<string>()
        if (op === '=') {
          if (left && (literal(expr.right) || (right && known(right, covered)))) result.add(left)
          if (right && (literal(expr.left) || (left && known(left, covered)))) result.add(right)
        }
        if (
          op === 'IN' &&
          left &&
          expr.right?.type === 'expr_list' &&
          expr.right.value?.length &&
          expr.right.value.every(literal)
        )
          result.add(left)
        return result
      }
      const covered = new Set([...local].filter(([, safe]) => safe).map(([name]) => name))
      for (let pass = 0; pass <= refs.length; pass++) {
        const size = covered.size
        const add = (names: Set<string>, allowed = new Set(local.keys())) => {
          for (const name of names) if (local.has(name) && allowed.has(name)) covered.add(name)
        }
        add(prove(query.where, covered))
        for (let i = 0; i < refs.length; i++) {
          const ref = refs[i],
            join = (ref.join || '').toUpperCase()
          const name = normalize(ref.as || ref.table)
          // An outer JOIN ON condition never filters the preserved side.
          const allowed = join.includes('LEFT')
            ? new Set([name])
            : join.includes('RIGHT')
            ? new Set<string>(refs.slice(0, i).map(r => normalize(r.as || r.table)))
            : join.includes('FULL')
            ? new Set<string>()
            : new Set(local.keys())
          add(prove(ref.on, covered), allowed)
        }
        if (size === covered.size) break
      }
      for (const [name, row] of rows) row.covered = covered.has(name)
      const context = new Map(outer)
      for (const name of local.keys()) context.set(name, covered.has(name))
      // Correlated subqueries may inherit proven outer scope, never the other way round.
      const scan = (value: any) => {
        if (!value || typeof value !== 'object') return
        if (value.type === 'select') {
          visit(value, context, ctes)
          return
        }
        for (const [key, child] of Object.entries(value)) if (key !== 'loc') scan(child)
      }
      scan(query.where)
      scan(query.having)
      scan(query.columns)
      for (const ref of refs) scan(ref.on)
      const safe = local.size > 0 && covered.size === local.size
      const nextSafe = query._next ? visit(query._next, outer, ctes) : true
      return safe && nextSafe
    }
    for (const query of Array.isArray(ast) ? ast : [ast]) visit(query)
    return { tables: tables.sort((a, b) => a.from - b.from), parsed: true }
  } catch {
    // Incomplete/unsupported SQL must never retain stale green highlights.
    const masked = source.replace(/'(?:''|\\.|[^'\\])*'|--[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\//g, s =>
      ' '.repeat(s.length)
    )
    const pattern = new RegExp(
      `\\b(?:FROM|JOIN)\\s+(${identifier}(?:\\s*\\.\\s*${identifier})*)`,
      'giu'
    )
    const fallback: TenantTableCheck[] = []
    for (const match of masked.matchAll(pattern)) {
      const from = match.index + match[0].indexOf(match[1])
      fallback.push({
        name: source.slice(from, from + match[1].length),
        alias: '',
        from,
        to: from + match[1].length,
        covered: false
      })
    }
    return { tables: fallback, parsed: false }
  }
}
