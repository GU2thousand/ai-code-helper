#!/usr/bin/env python3
"""Bind a running Compose verification stack to source and actual image identities."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default=os.getenv('COMPOSE_PROJECT_NAME', 'ai-code-helper'))
    parser.add_argument('--output', type=Path, default=ROOT / 'output/ai-system/verification-manifest.json')
    parser.add_argument('--require-clean', action='store_true', help='Require clean source and matching backend/frontend revision labels')
    args = parser.parse_args()
    revision = command('git', 'rev-parse', 'HEAD')
    clean = not command('git', 'status', '--porcelain', '--untracked-files=normal')
    files = [ROOT / name for name in ['docker-compose.yml', 'backend/Dockerfile', 'frontend/Dockerfile',
        'frontend/nginx.conf', 'backend/src/main/resources/application.yml',
        'backend/src/main/resources/application-local.yml']]
    files += sorted(path for path in (ROOT / 'deployment').rglob('*')
                    if path.is_file() and path.suffix in {'.yml', '.yaml', '.json', '.sql'})
    config_hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
    # Inspect only selected metadata: never dump Config.Env or rendered Compose secrets.
    container_format = '{"id":{{json .Id}},"imageId":{{json .Image}},"imageReference":{{json .Config.Image}},"createdAt":{{json .Created}},"state":{{json .State.Status}},"service":{{json (index .Config.Labels "com.docker.compose.service")}},"composeConfigHash":{{json (index .Config.Labels "com.docker.compose.config-hash")}}}'
    image_format = '{"id":{{json .Id}},"createdAt":{{json .Created}},"architecture":{{json .Architecture}},"os":{{json .Os}},"sourceRevision":{{if .Config.Labels}}{{json (index .Config.Labels "org.opencontainers.image.revision")}}{{else}}null{{end}},"repoDigests":{{json .RepoDigests}}}'
    ids = command('docker', 'compose', '-p', args.project, 'ps', '--all', '--quiet').splitlines()
    containers = []
    for container_id in ids:
        container = json.loads(command('docker', 'inspect', '--format', container_format, container_id))
        container['image'] = json.loads(command('docker', 'image', 'inspect', '--format', image_format, container['imageId']))
        containers.append(container)
    by_service = {entry['service']: entry for entry in containers}
    labelled = all(service in by_service and by_service[service]['image']['sourceRevision'] == revision
                   for service in ('backend', 'frontend'))
    manifest = {
        'schemaVersion': 1,
        'recordedAt': datetime.now(timezone.utc).isoformat(),
        'sourceCommit': revision,
        'sourceClean': clean,
        'applicationImagesMatchSourceRevision': labelled,
        'composeProject': args.project,
        'dockerServerVersion': command('docker', 'version', '--format', '{{.Server.Version}}'),
        'configFileSha256': config_hashes,
        'containers': sorted(containers, key=lambda entry: entry['service']),
        'scope': 'Observed local containers and image labels; no provider credentials or environment values recorded.',
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'sourceCommit': revision, 'sourceClean': clean,
                      'applicationImagesMatchSourceRevision': labelled, 'containers': len(containers),
                      'output': str(args.output)}))
    return 0 if not args.require_clean or (clean and labelled and all(c['state'] == 'running' for c in containers)) else 1


if __name__ == '__main__':
    raise SystemExit(main())
