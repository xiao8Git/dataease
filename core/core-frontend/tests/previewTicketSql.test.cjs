const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const source = fs.readFileSync(path.join(__dirname, '../src/views/visualized/data/dataset/form/previewTicketSql.ts'), 'utf8')
const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 } }).outputText
const mod = { exports: {} }
new Function('module', 'exports', output)(mod, mod.exports)
const { bindPreviewTicketSql: bind } = mod.exports
const sql = "SELECT '__DE_TICKET_TENANT__', '__DE_TICKET_USER__', '__DE_TICKET_TENANT__'"
assert.equal(bind(sql, { tenant: 'wunding', user: 'admin' }), "SELECT 'wunding', 'admin', 'wunding'")
assert.equal(bind(sql, { tenant: '', user: '' }), 'SELECT NULL, NULL, NULL')
assert.equal(bind(sql, { tenant: 'jochu', user: '' }), "SELECT 'jochu', NULL, 'jochu'")
for (const value of ["' OR 1=1--", 'a\\b', 'a b', 'a'.repeat(129)]) {
  assert.throws(() => bind(sql, { tenant: value, user: 'admin' }))
}
assert.equal(sql, "SELECT '__DE_TICKET_TENANT__', '__DE_TICKET_USER__', '__DE_TICKET_TENANT__'")
console.log('Preview ticket SQL tests passed')
