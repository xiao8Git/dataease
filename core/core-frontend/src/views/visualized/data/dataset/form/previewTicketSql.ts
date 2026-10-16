/** Bind a copy for the authenticated SQL editor's Run request; never mutate saved SQL. */
export function bindPreviewTicketSql(
  sql: string,
  values: { tenant: string; user: string }
): string {
  const literal = (value: string) => {
    if (!value) return 'NULL'
    if (!/^[A-Za-z0-9_.:@/-]{1,128}$/.test(value)) throw new Error('Invalid preview identity')
    return "'" + value + "'"
  }
  const tenant = literal(values.tenant)
  const user = literal(values.user)
  return sql.replaceAll("'__DE_TICKET_TENANT__'", tenant).replaceAll("'__DE_TICKET_USER__'", user)
}
