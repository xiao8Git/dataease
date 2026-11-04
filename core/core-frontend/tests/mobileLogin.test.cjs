const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { test } = require('node:test')
const ts = require('typescript')

// Exercise the actual submit handler with API/store boundaries stubbed.
const source = fs
  .readFileSync(path.join(__dirname, '../src/views/mobile/login/index.vue'), 'utf8')
  .match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
const ast = ts.createSourceFile('mobile-login.ts', source, ts.ScriptTarget.Latest, true)
const declarations = ast.statements
  .filter(ts.isVariableStatement)
  .flatMap(s => s.declarationList.declarations)
const handler = declarations.find(d => d.name.getText(ast) === 'onSubmit').initializer.getText(ast)
const compiled = ts.transpileModule(`const onSubmit = ${handler}; return onSubmit`, {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText

function setup(options = {}) {
  const submitted = []
  const tokens = []
  const routes = []
  const pluginCalls = []
  const toasts = []
  const busy = { value: false }
  const context = {
    username: { value: options.username || ' mobile.user ' },
    password: { value: options.password || 'password123456' },
    duringLogin: busy,
    wsCache: { get: () => 'cached-key', set() {} },
    appStore: { getDekey: 'dekey' },
    queryDekey: async () => ({ data: 'fresh-key' }),
    rsaEncryp: value => `encrypted:${value}`,
    loginType: { value: options.ldap ? 'ldap' : 'default' },
    xpackLoadFail: { value: false },
    xpackInvalidPwd: { value: { invokeMethod: p => pluginCalls.push(p) } },
    showMfa: { value: false },
    toMfa: () => !!options.mfa,
    userStore: {
      setToken: token => tokens.push(token),
      setExp() {},
      setTime() {}
    },
    router: { push: route => routes.push(route) },
    showToast: toast => toasts.push(toast),
    t: key => key,
    loginApi: async param => {
      submitted.push(param)
      if (options.reject) throw new Error('Invalid credentials')
      return { data: { token: 'test-token', exp: 123, localAccess: options.localAccess !== false } }
    }
  }
  const submit = new Function(...Object.keys(context), compiled)(...Object.values(context))
  return { submit, submitted, tokens, routes, pluginCalls, toasts, busy }
}
async function run(options) {
  const state = setup(options)
  await state.submit()
  await new Promise(resolve => setImmediate(resolve))
  return state
}

for (const password of [
  'password123456',
  'A'.repeat(64),
  'space in password ',
  '中文口令测试123456'
]) {
  test(`server validates existing password (${password.length} characters)`, async () => {
    const state = await run({ password })
    assert.deepEqual(state.submitted, [
      { name: 'encrypted:mobile.user', pwd: `encrypted:${password}`, origin: 0 }
    ])
    assert.deepEqual(state.tokens, ['test-token'])
    assert.deepEqual(state.routes, [{ path: '/index' }])
    assert.equal(state.pluginCalls.length, 0)
    assert.equal(state.toasts.length, 0)
  })
}
test('username is trimmed and not rejected by client format rules', async () => {
  const state = await run({ username: ' 手机用户 ' })
  assert.equal(state.submitted[0].name, 'encrypted:手机用户')
})
test('incorrect password remains rejected by server', async () => {
  const state = await run({ reject: true })
  assert.equal(state.submitted.length, 1)
  assert.deepEqual(state.tokens, [])
  assert.deepEqual(state.routes, [])
  assert.equal(state.busy.value, false)
})
test('non-local login still invokes password/MFA plugin', async () => {
  const state = await run({ localAccess: false })
  assert.equal(state.pluginCalls.length, 1)
  assert.deepEqual(state.tokens, [])
  assert.deepEqual(state.routes, [])
})
test('LDAP login retains its origin and bypasses password plugin', async () => {
  const state = await run({ ldap: true, localAccess: false })
  assert.equal(state.submitted[0].origin, 1)
  assert.equal(state.pluginCalls.length, 0)
  assert.deepEqual(state.routes, [{ path: '/index' }])
})
test('MFA does not establish a session before completion', async () => {
  const state = await run({ mfa: true })
  assert.deepEqual(state.tokens, [])
  assert.deepEqual(state.routes, [])
})
