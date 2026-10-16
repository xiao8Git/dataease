import { format, type SqlLanguage } from 'sql-formatter'

const dialects: Record<string, SqlLanguage> = {
  mysql: 'mysql',
  mariadb: 'mariadb',
  doris: 'mysql',
  starrocks: 'mysql',
  pg: 'postgresql',
  postgresql: 'postgresql',
  sqlserver: 'transactsql',
  oracle: 'plsql',
  ck: 'clickhouse',
  clickhouse: 'clickhouse',
  redshift: 'redshift',
  hive: 'hive',
  sparksql: 'spark',
  db2: 'db2',
  trino: 'trino',
  presto: 'trino'
}

export function formatDatasetSql(source: string, datasourceType?: string): string {
  if (!source.trim()) return source
  return format(source, {
    language: dialects[datasourceType?.toLowerCase()] || 'sql',
    tabWidth: 2,
    keywordCase: 'preserve',
    paramTypes: {
      custom: [
        { regex: String.raw`\$\{[^}]+\}` },
        { regex: String.raw`\$DE_PARAM\{[^}]+\}` },
        { regex: String.raw`\$f2cde\[[^\]]+\]` }
      ]
    }
  })
}
