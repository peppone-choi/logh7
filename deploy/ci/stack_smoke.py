#!/usr/bin/env python3
"""작성: Peppone Choi. GitHub-hosted 전용 일회성 Compose smoke; 운영 데이터에 사용 금지."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time
import urllib.error
import urllib.request


class Smoke:
    def __init__(self):
        self.project = os.environ['COMPOSE_PROJECT_NAME']
        if not re.fullmatch(r'logh7-stack-ci-[0-9]+-[0-9]+', self.project):
            raise ValueError('Only isolated stack CI project names are permitted')
        self.directory = Path(os.environ['RUNNER_TEMP']) / self.project
        self.base = Path(__file__).resolve().parents[1] / 'docker-compose.yml'

    def command(self, *args):
        # Never echo arguments, environment, Compose config, or container logs.
        result = subprocess.run(args, text=True, capture_output=True, timeout=180)
        if result.returncode:
            raise RuntimeError('Docker operation failed (output suppressed for secret safety)')
        return result.stdout.strip()

    def compose(self, *args):
        return self.command('docker', 'compose', '--project-name', self.project,
                            '--env-file', '/dev/null', '-f', str(self.base),
                            '-f', str(self.directory / 'override.json'), '--profile', 'game', *args)

    def prepare(self):
        self.directory.mkdir(mode=0o700, parents=False, exist_ok=False)
        overrides = {'secrets': {}}
        for name in ('postgres_password', 'grafana_password'):
            path = self.directory / name
            path.write_text(secrets.token_hex(32) + '\n')
            # Grafana runs as UID 472/GID 0; postgres entrypoint starts as root.
            path.chmod(0o640 if name == 'grafana_password' else 0o600)
            if name == 'grafana_password':
                self.command('sudo', 'chgrp', '0', str(path))
            overrides['secrets'][name] = {'file': str(path)}
        (self.directory / 'override.json').write_text(json.dumps(overrides))

    def wait(self, check, label, attempts=60):
        for attempt in range(attempts):
            try:
                check()
                print(label + ': ready', flush=True)
                return
            except (RuntimeError, OSError, ValueError):
                if attempt + 1 < attempts:
                    time.sleep(2)
        raise RuntimeError(label + ': readiness timed out')

    def http(self, url, grafana=False):
        with urllib.request.urlopen(url, timeout=3) as response:
            if response.status != 200:
                raise RuntimeError('HTTP not ready')
            if grafana and json.load(response).get('database') != 'ok':
                raise RuntimeError('Grafana database not ready')

    def ready(self):
        self.wait(lambda: self.compose('exec', '-T', 'postgres', 'pg_isready',
                                      '-U', 'logh7', '-d', 'logh7'), 'PostgreSQL')
        self.wait(lambda: self.http('http://127.0.0.1:9090/-/ready'), 'Prometheus')
        self.wait(lambda: self.http('http://127.0.0.1:3000/api/health', True), 'Grafana')
        self.wait(lambda: self.compose('exec', '-T', 'game-server', 'bash', '-ec', '''
exec 3<>/dev/tcp/127.0.0.1/47901
printf 'GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r status <&3
[[ "$status" == *" 200 "* ]]
for port in 47900 47902 47903; do (exec 4<>/dev/tcp/127.0.0.1/$port); done
'''), 'Game HTTP and internal loopback TCP')

    def sql(self, query):
        return self.compose('exec', '-T', 'postgres', 'psql', '-U', 'logh7',
                            '-d', 'logh7', '-At', '-v', 'ON_ERROR_STOP=1', '-c', query)

    def run(self):
        try:
            self.prepare()
            self.compose('up', '-d', '--no-build', '--wait', '--wait-timeout', '120')
            self.ready()
            marker = secrets.token_hex(16)
            self.sql('CREATE TABLE ci_smoke (id integer PRIMARY KEY, marker text NOT NULL);')
            self.sql(f"INSERT INTO ci_smoke VALUES (1, '{marker}');")
            self.compose('restart', 'postgres')
            self.wait(lambda: self.compose('exec', '-T', 'postgres', 'pg_isready',
                                          '-U', 'logh7', '-d', 'logh7'), 'PostgreSQL after restart')
            if self.sql('SELECT marker FROM ci_smoke WHERE id = 1;') != marker:
                raise RuntimeError('PostgreSQL restart persistence mismatch')
        finally:
            self.cleanup()
        print('STACK_SMOKE_OK: four services ready; PostgreSQL restart marker retained; cleanup verified', flush=True)

    def cleanup(self):
        # Fallback base config needs no secret content to run down. The CI override
        # is retained until down succeeds, so the always() step can retry failures.
        if (self.directory / 'override.json').exists():
            self.compose('down', '-v', '--remove-orphans', '--timeout', '10')
        else:
            self.command('docker', 'compose', '--project-name', self.project,
                         '--env-file', '/dev/null', '-f', str(self.base),
                         '--profile', 'game', 'down', '-v', '--remove-orphans', '--timeout', '10')
        for kind, args in (('containers', ('ps', '-aq')), ('volumes', ('volume', 'ls', '-q'))):
            remaining = self.command('docker', *args, '--filter',
                                     'label=com.docker.compose.project=' + self.project)
            if remaining:
                raise RuntimeError('CLEANUP_FAILED: remaining ' + kind)
        if self.directory.exists():
            shutil.rmtree(self.directory)
        print('STACK_CLEANUP_OK: no project containers or volumes; temporary secrets removed', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('run', 'cleanup'))
    args = parser.parse_args()
    getattr(Smoke(), args.action)()
