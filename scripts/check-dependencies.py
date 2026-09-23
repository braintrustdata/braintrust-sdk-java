#!/usr/bin/env python3
"""Check the current working tree's shipped dependencies against GitHub advisories."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parent.parent
INIT_SCRIPT = """
initscript {
  repositories { maven { url = uri('https://plugins.gradle.org/m2/') } }
  dependencies {
    classpath "org.gradle:github-dependency-graph-gradle-plugin:${System.getenv('DEPENDENCY_GRAPH_PLUGIN_VERSION')}"
  }
}

// JVM properties override the sanitized environment, including inherited exclusions.
System.properties.stringPropertyNames().findAll { key ->
  key.startsWith('DEPENDENCY_GRAPH_') || key.startsWith('GITHUB_DEPENDENCY_GRAPH_')
}.each { key -> System.clearProperty(key) }

apply plugin: org.gradle.github.GitHubDependencyGraphPlugin

// The graph plugin can omit unresolved dependencies. Refuse a partial scan.
gradle.projectsEvaluated {
  def root = gradle.rootProject
  root.tasks.register('checkLocalDependencyResolution') {
    dependsOn ':ForceDependencyResolutionPlugin_resolveAllDependencies'
    doLast {
      root.allprojects.each { project ->
        if (project.path ==~ System.getenv('DEPENDENCY_GRAPH_INCLUDE_PROJECTS')) {
          project.configurations.each { config ->
            if (config.canBeResolved &&
                config.name ==~ System.getenv('DEPENDENCY_GRAPH_INCLUDE_CONFIGURATIONS')) {
              config.incoming.resolutionResult.allDependencies.each { dependency ->
                if (dependency instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                  throw new GradleException("Cannot scan ${project.path}:${config.name}", dependency.failure)
                }
              }
            }
          }
        }
      }
    }
  }
}
"""


def run(command, **kwargs):
    result = subprocess.run(
        command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, **kwargs
    )
    if result.returncode:
        detail = "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
        raise RuntimeError(f"{command[0]} exited with status {result.returncode}:\n{detail}")
    return result.stdout


def shipped_packages():
    settings = json.loads((ROOT / '.github/dependency-graph.json').read_text())
    # Inherited submission settings must not silently exclude local dependencies.
    environment = {
        key: value for key, value in os.environ.items()
        if not key.startswith(('DEPENDENCY_GRAPH_', 'GITHUB_DEPENDENCY_GRAPH_'))
    }
    environment.update(settings)
    with tempfile.TemporaryDirectory(prefix='braintrust-dependencies-') as directory:
        temporary = Path(directory)
        init_script = temporary / 'scan.init.gradle'
        init_script.write_text(INIT_SCRIPT)
        environment.update({
            'GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR': 'local-check',
            'GITHUB_DEPENDENCY_GRAPH_JOB_ID': 'local-check',
            'GITHUB_DEPENDENCY_GRAPH_REF': 'refs/heads/local-check',
            'GITHUB_DEPENDENCY_GRAPH_SHA': run(['git', 'rev-parse', 'HEAD']).strip(),
            'GITHUB_DEPENDENCY_GRAPH_WORKSPACE': str(ROOT),
            'DEPENDENCY_GRAPH_REPORT_DIR': str(temporary / 'reports'),
        })
        run([
            str(ROOT / 'gradlew'), '--quiet', '--console=plain',
            '--no-configuration-cache', '--no-configure-on-demand',
            '-I', str(init_script), ':checkLocalDependencyResolution',
        ], env=environment)
        snapshot = json.loads((temporary / 'reports/local-check.json').read_text())
        packages = sorted({
            dependency['package_url']
            for manifest in snapshot['manifests'].values()
            for dependency in manifest['resolved'].values()
            if 'package_url' in dependency
        })
        if not packages:
            raise RuntimeError('The shipped dependency graph is empty; refusing to report a clean scan.')
        return packages


def advisories(package_url):
    if not package_url.startswith('pkg:maven/'):
        raise ValueError(f'Unsupported dependency ecosystem: {package_url}')
    coordinate = package_url[len('pkg:maven/'):].split('?', 1)[0].split('#', 1)[0]
    group, artifact_version = coordinate.split('/', 1)
    artifact, version = artifact_version.rsplit('@', 1)
    affected = f'{unquote(group)}:{unquote(artifact)}@{unquote(version)}'
    pages = json.loads(run([
        'gh', 'api', '--hostname', 'github.com', '--method', 'GET',
        '--paginate', '--slurp', '/advisories',
        '-f', 'type=reviewed', '-f', 'ecosystem=maven',
        '-f', f'affects={affected}', '-f', 'per_page=100',
    ]))
    active = {
        advisory['ghsa_id']: advisory
        for page in pages for advisory in page
        if not advisory.get('withdrawn_at')
    }
    return affected, sorted(active.values(), key=lambda advisory: advisory['ghsa_id'])


def scan(packages):
    print(f'Checking {len(packages)} shipped dependency versions against GitHub-reviewed advisories...', flush=True)
    # Collect every result before reporting success; an API failure is not a clean scan.
    with ThreadPoolExecutor(max_workers=6) as executor:
        results = list(executor.map(advisories, packages))
    findings = [(package, matches) for package, matches in results if matches]
    if not findings:
        print(f'PASS: No matching GitHub-reviewed advisories in {len(packages)} shipped dependency versions.')
        return 0
    for package, matches in findings:
        print(f'\n{package}')
        for advisory in matches:
            identifier = advisory.get('cve_id') or advisory['ghsa_id']
            print(f"  {advisory['severity'].upper()} {identifier}: {advisory['summary']}")
            print(f"  {advisory['html_url']}")
    count = sum(len(matches) for _, matches in findings)
    print(f'\nFAIL: {count} advisory matches across {len(findings)} shipped dependency versions.')
    return 1


def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        epilog='Requires Python 3.9+, JDK 17, and authenticated gh. Exit codes: 0 clean, 1 findings, 2 scan error. Nothing is submitted to GitHub.',
    )
    parser.parse_args()
    try:
        for executable in ('java', 'git', 'gh'):
            if not shutil.which(executable):
                raise RuntimeError(f'Required command not found: {executable}')
        print('Resolving shipped dependencies from the current working tree (including uncommitted changes)...', flush=True)
        return scan(shipped_packages())
    except (OSError, RuntimeError, ValueError, KeyError, TypeError) as error:
        print(f'ERROR: Dependency scan incomplete: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
