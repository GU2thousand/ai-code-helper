import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import { marked } from 'marked'

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;')
}

const renderer = new marked.Renderer()

renderer.html = () => ''

renderer.image = (_href, _title, text) => {
  const label = String(text || '').trim()
  return label ? `<span class="markdown-image-placeholder">[图片已隐藏：${escapeHtml(label)}]</span>` : ''
}

renderer.code = (code, languageHint = '') => {
  const language = String(languageHint).trim().split(/\s+/)[0].toLowerCase()
  let highlighted
  let label = language || 'text'

  if (language && hljs.getLanguage(language)) {
    highlighted = hljs.highlight(code, { language, ignoreIllegals: true }).value
  } else {
    highlighted = hljs.highlightAuto(code).value
    label = language || highlighted.language || 'text'
  }

  return `<div class="code-block">
    <div class="code-block__header">
      <span>${escapeHtml(label)}</span>
      <button type="button" class="code-copy-button" data-copy-code aria-label="复制代码">复制代码</button>
    </div>
    <pre><code class="hljs language-${escapeHtml(label)}">${highlighted}</code></pre>
  </div>`
}

renderer.link = (href, title, text) => {
  const titleAttribute = title ? ` title="${escapeHtml(title)}"` : ''
  return `<a href="${escapeHtml(href)}"${titleAttribute} target="_blank" rel="noopener noreferrer">${text}</a>`
}

marked.setOptions({
  renderer,
  gfm: true,
  breaks: true,
  mangle: false,
  headerIds: false
})

export function renderMarkdown(markdown) {
  const html = marked.parse(markdown || '')
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      'a', 'blockquote', 'br', 'button', 'code', 'del', 'div', 'em', 'h1', 'h2', 'h3',
      'h4', 'h5', 'h6', 'hr', 'li', 'ol', 'p', 'pre', 'span', 'strong', 'table', 'tbody',
      'td', 'th', 'thead', 'tr', 'ul'
    ],
    ALLOWED_ATTR: [
      'aria-label', 'class', 'data-copy-code', 'href', 'rel', 'target', 'title', 'type'
    ],
    ALLOW_ARIA_ATTR: false,
    ALLOW_DATA_ATTR: false,
    FORBID_TAGS: ['form', 'iframe', 'img', 'input', 'object', 'script', 'style', 'svg', 'textarea']
  })
}
