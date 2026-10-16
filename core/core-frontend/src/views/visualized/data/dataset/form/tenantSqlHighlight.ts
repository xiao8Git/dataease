import { StateEffect, StateField } from '@codemirror/state'
import { Decoration, EditorView, ViewPlugin } from '@codemirror/view'
import { checkTenantSql, type TenantSqlCheck } from './tenantSqlCheck'

export function tenantSqlHighlight(
  report: (result: TenantSqlCheck) => void,
  labels: { covered: string; missing: string }
) {
  const resultEffect = StateEffect.define<TenantSqlCheck>()
  const marks = StateField.define({
    create: () => Decoration.none,
    update: (value, transaction) => {
      if (transaction.docChanged) return Decoration.none
      for (const effect of transaction.effects) {
        if (effect.is(resultEffect)) {
          return Decoration.set(
            effect.value.tables.map(table =>
              Decoration.mark({
                class: table.covered ? 'sql-tenant-covered' : 'sql-tenant-missing',
                attributes: { title: table.covered ? labels.covered : labels.missing }
              }).range(table.from, table.to)
            ),
            true
          )
        }
      }
      return value
    },
    provide: field => EditorView.decorations.from(field)
  })
  const plugin = ViewPlugin.fromClass(
    class {
      timer: ReturnType<typeof setTimeout>
      constructor(readonly view: EditorView) {
        this.schedule()
      }
      schedule() {
        clearTimeout(this.timer)
        this.timer = setTimeout(() => {
          const result = checkTenantSql(this.view.state.doc.toString())
          this.view.dispatch({ effects: resultEffect.of(result) })
          report(result)
        }, 300)
      }
      update(update) {
        if (update.docChanged) this.schedule()
      }
      destroy() {
        clearTimeout(this.timer)
      }
    }
  )
  return [
    marks,
    plugin,
    EditorView.baseTheme({
      '.sql-tenant-covered, .sql-tenant-covered span': {
        color: '#16803c !important',
        textDecoration: 'underline',
        textDecorationColor: '#16803c'
      },
      '.sql-tenant-missing, .sql-tenant-missing span': {
        color: '#d03036 !important',
        textDecoration: 'underline wavy',
        textDecorationColor: '#d03036'
      }
    })
  ]
}
