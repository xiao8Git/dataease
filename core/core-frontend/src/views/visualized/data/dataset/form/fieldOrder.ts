export function moveField<T extends { id: string | number; columnIndex?: number }>(
  fields: T[],
  id: string | number,
  position: number
): T[] {
  const index = fields.findIndex(field => field.id === id)
  if (index < 0 || !Number.isInteger(position) || position < 1 || position > fields.length) {
    return fields
  }
  const result = [...fields]
  const [field] = result.splice(index, 1)
  result.splice(position - 1, 0, field)
  return result.map((item, columnIndex) => ({ ...item, columnIndex }))
}
