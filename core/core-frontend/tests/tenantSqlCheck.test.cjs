const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const source = fs.readFileSync(
  path.join(__dirname, '../src/views/visualized/data/dataset/form/tenantSqlCheck.ts'),
  'utf8'
)
const output = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const mod = { exports: {} }
new Function('require', 'module', 'exports', output)(require, mod, mod.exports)
const { checkTenantSql } = mod.exports
function check(sql, expected, parsed = true) {
  const result = checkTenantSql(sql)
  assert.equal(result.parsed, parsed, sql)
  assert.deepEqual(
    result.tables.map(t => [t.name, t.covered]),
    expected,
    sql
  )
  for (const row of result.tables) assert.equal(sql.slice(row.from, row.to), row.name)
}
check('select * from a', [['a', false]])
check("select * from a where tenant_id='wunding'", [['a', true]])
check('select * from a where tenant_id is not null', [['a', false]])
check('select tenant_id=1 from a', [['a', false]])
check("select 'tenant_id=1' from a -- tenant_id=1", [['a', false]])
check('select * from a where a.tenant_id=a.tenant_id', [['a', false]])
check('select * from a join b on a.tenant_id=b.tenant_id', [
  ['a', false],
  ['b', false]
])
check('select * from a join b on a.tenant_id=b.tenant_id where a.tenant_id=1', [
  ['a', true],
  ['b', true]
])
check('select * from a,b where a.tenant_id=1', [
  ['a', true],
  ['b', false]
])
check('select * from a,b where tenant_id=1', [
  ['a', false],
  ['b', false]
])
check('select * from a where a.tenant_id=1 or a.enabled=1', [['a', false]])
check('select * from a where a.tenant_id=1 or a.tenant_id=2', [['a', true]])
check('select * from a left join b on a.tenant_id=1 and b.tenant_id=a.tenant_id', [
  ['a', false],
  ['b', false]
])
check('select * from a left join b on b.tenant_id=a.tenant_id where a.tenant_id=1', [
  ['a', true],
  ['b', true]
])
check('select * from a right join b on a.tenant_id=b.tenant_id where b.tenant_id=1', [
  ['a', true],
  ['b', true]
])
check(
  'select * from a where a.tenant_id=1 and exists(select 1 from b where b.tenant_id=a.tenant_id)',
  [
    ['a', true],
    ['b', true]
  ]
)
check(
  'select * from a x where x.tenant_id=1 and exists(select 1 from b x where x.tenant_id=x.tenant_id)',
  [
    ['a', true],
    ['b', false]
  ]
)
check('select * from a where exists(select 1 from b where b.tenant_id=1)', [
  ['a', false],
  ['b', true]
])
check('select * from a where tenant_id=1 union all select * from b', [
  ['a', true],
  ['b', false]
])
check('with x as (select * from a where tenant_id=1) select * from x', [
  ['a', true],
  ['x', true]
])
check('select * from (select * from a where tenant_id=1) x', [['a', true]])
check('select * from `ods`.`a` x where x.`tenant_id`=1', [['`ods`.`a`', true]])
for (const param of [
  '${tenant_id}',
  '$DE_PARAM{tenant_id}',
  '$f2cde[租户]',
  "'__DE_TICKET_TENANT__'"
]) {
  check('select * from a where tenant_id=' + param, [['a', true]])
}
check('select * from a where tenant_id in (1,2)', [['a', true]])
check('select * from a where tenant_id in (select tenant_id from b)', [
  ['a', false],
  ['b', false]
])
check('select cast(id AS STRING) from a where tenant_id=1', [['a', true]])
check('select * from a where (', [['a', false]], false)
check('', [])
console.log('Tenant SQL analysis regression checks passed')
