import { afterEach, vi } from 'vitest'

const storage = new Map()
const localStorageMock = {
  getItem: (key) => storage.get(String(key)) ?? null,
  setItem: (key, value) => storage.set(String(key), String(value)),
  removeItem: (key) => storage.delete(String(key)),
  clear: () => storage.clear(),
  key: (index) => [...storage.keys()][index] ?? null,
  get length() { return storage.size }
}

Object.defineProperty(globalThis, 'localStorage', {
  value: localStorageMock,
  configurable: true
})

afterEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
})

if (!window.matchMedia) {
  window.matchMedia = vi.fn().mockImplementation((query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn()
  }))
}

if (!Element.prototype.scrollTo) {
  Element.prototype.scrollTo = vi.fn()
}
