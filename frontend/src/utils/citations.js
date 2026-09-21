const CHUNK_ID = /^[a-zA-Z0-9._:-]{1,160}$/
const CITATION_MARKER = /\[chunk:([a-zA-Z0-9._:-]{1,160})\]/g

// Only call this with sanitized Markdown. All source-derived values are inserted
// through text/attribute APIs; source URLs never become navigation targets.
export function renderCitationReferences(sanitizedHtml, sources = []) {
  const template = document.createElement('template')
  template.innerHTML = sanitizedHtml
  const sourceIndexes = new Map()
  const ambiguousIds = new Set()

  sources.forEach((source, index) => {
    const id = source?.chunkId
    if (typeof id !== 'string' || !CHUNK_ID.test(id)) return
    if (sourceIndexes.has(id)) ambiguousIds.add(id)
    else sourceIndexes.set(id, index)
  })

  const walker = document.createTreeWalker(template.content, NodeFilter.SHOW_TEXT)
  const textNodes = []
  while (walker.nextNode()) {
    const node = walker.currentNode
    // Code examples and link labels remain literal and never contain controls.
    if (!node.parentElement?.closest('pre, code, a, button')) textNodes.push(node)
  }

  for (const node of textNodes) {
    const matches = [...node.textContent.matchAll(CITATION_MARKER)]
    if (!matches.length) continue
    const fragment = document.createDocumentFragment()
    let offset = 0
    for (const match of matches) {
      fragment.append(document.createTextNode(node.textContent.slice(offset, match.index)))
      const id = match[1]
      const index = ambiguousIds.has(id) ? undefined : sourceIndexes.get(id)
      const reference = document.createElement(index === undefined ? 'span' : 'button')
      if (index === undefined) {
        reference.className = 'citation-unmatched'
        reference.textContent = `[未匹配引用：${id}]`
        reference.setAttribute('title', '此引用未匹配到返回的参考资料')
      } else {
        reference.className = 'citation-reference'
        reference.setAttribute('type', 'button')
        reference.setAttribute('data-citation-index', String(index))
        reference.setAttribute('aria-label', `查看参考资料 ${index + 1}：${sources[index].title || '未命名资料'}`)
        reference.setAttribute('title', String(sources[index].title || '未命名资料'))
        reference.textContent = `[${index + 1}]`
      }
      fragment.append(reference)
      offset = match.index + match[0].length
    }
    fragment.append(document.createTextNode(node.textContent.slice(offset)))
    node.replaceWith(fragment)
  }
  return template.innerHTML
}
