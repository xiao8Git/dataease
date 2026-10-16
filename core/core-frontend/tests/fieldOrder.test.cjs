const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const source = fs.readFileSync(path.join(__dirname, '../src/views/visualized/data/dataset/form/fieldOrder.ts'), 'utf8')
const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const mod = { exports: {} }
new Function('module', 'exports', output)(mod, mod.exports)
const { moveField } = mod.exports
const fields = [{id:'a',groupType:'d'}, {id:'q',groupType:'q'}, {id:'b',groupType:'d'}]
assert.deepEqual(moveField(fields,'b',1).map(x=>x.id),['b','a','q'])
assert.deepEqual(moveField(fields,'a',3).map(x=>x.columnIndex),[0,1,2])
assert.deepEqual(fields.map(x=>x.id),['a','q','b'])
for(const value of [0,4,null,1.5,NaN]) assert.equal(moveField(fields,'a',value),fields)
assert.equal(moveField(fields,'unknown',1),fields)
console.log('Field order tests passed')
