"""Turn collected observations into a required regression gate, including harness failures."""
import json
from pathlib import Path
root = Path('output/playwright')
results = []
for name in ('core', 'edge', 'restart', 'streaming', 'contracts'):
    text = (root / f'browser-{name}.log').read_text()
    assert text.startswith('### Result\n'), f'Browser harness failed: {name}'
    group = json.loads(text.split('### Result\n', 1)[1].split('\n### ', 1)[0])
    assert isinstance(group, list) and group, f'No checks from {name}'
    results.extend(group)
for name in ('api-results', 'stable-restart'):
    results.extend(json.loads((root / f'{name}.json').read_text()))
failed = [result for result in results if not result['passed']]
print(json.dumps({'total':len(results), 'passed':len(results)-len(failed), 'failed':failed}, ensure_ascii=False, indent=2))
assert len(results) >= 54, 'Missing runtime regression checks'
assert not failed, f'{len(failed)} runtime checks failed'
