/**
 * 前端数据导出工具（CSV / JSON）
 */

function toCsvRow(values: (string | number | boolean | null | undefined)[]): string {
  return values.map(v => {
    if (v == null) return ''
    const s = String(v)
    if (s.includes(',') || s.includes('"') || s.includes('\n')) {
      return `"${s.replace(/"/g, '""')}"`
    }
    return s
  }).join(',')
}

export function exportCsv(filename: string, headers: string[], rows: (string | number | boolean | null | undefined)[][]) {
  const bom = '\uFEFF'
  const content = bom + [toCsvRow(headers), ...rows.map(toCsvRow)].join('\n')
  downloadFile(filename, content, 'text/csv;charset=utf-8')
}

export function exportJson(filename: string, data: unknown) {
  const content = JSON.stringify(data, null, 2)
  downloadFile(filename, content, 'application/json;charset=utf-8')
}

function downloadFile(filename: string, content: string, mimeType: string) {
  const blob = new Blob([content], { type: mimeType })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
