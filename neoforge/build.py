#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline NeoForge build of RTX Remix for Minecraft.

No Maven access is needed: everything is built from jars already installed by
the CurseForge launcher (Minecraft 1.21.1 + NeoForge 21.1.x), Yarn and
Intermediary mappings from GitHub, and NVIDIA's release jar (for the native
RTX Remix runtime, which is not built here).

Stages (each is cached in the work dir and can be run separately):

  prepare   mappings, Mojang->Yarn remap of Minecraft and NeoForge, classpath
  compile   javac of upstream sources + the NeoForge compat layer, with the
            Mixin annotation processor producing the refmap
  package   Yarn->Mojang remap of the result, resources, natives, mods.toml
  verify    static link check and mixin target check against the runtime jars

Configuration comes from environment variables (see CONFIG below).
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(HERE, 'tools'))

CONFIG = {
    'INSTALL': os.environ.get('RTX_INSTALL', os.path.expanduser('~/mnt/minecraft/Install')),
    'WORK': os.environ.get('RTX_WORK', os.path.expanduser('~/rtx/work')),
    'JDK': os.environ.get('RTX_JDK', os.path.expanduser('~/rtx/jdk')),
    'YARN': os.environ.get('RTX_YARN', os.path.expanduser('~/rtx/yarn/mappings')),
    'INTERMEDIARY': os.environ.get('RTX_INTERMEDIARY', os.path.expanduser('~/rtx/dl/intermediary-1.21.1.tiny')),
    'RELEASE_JAR': os.environ.get('RTX_RELEASE_JAR', os.path.expanduser(
        '~/rtx/dl/rel/mods/rtx-remix-renderer-0.1.0-202610230634-170197a.jar')),
    'MC': '1.21.1',
    'NEOFORM': '1.21.1-20240808.144430',
    'NEOFORGE': os.environ.get('RTX_NEOFORGE', '21.1.249'),
}

MOD_ID = 'rtx_remix_renderer'


def log(*a):
    print('[build]', *a, flush=True)


def W(*p):
    return os.path.join(CONFIG['WORK'], *p)


def lib(*p):
    return os.path.join(CONFIG['INSTALL'], 'libraries', *p)


def java(name='java'):
    return os.path.join(CONFIG['JDK'], 'bin', name)


def run(cmd, **kw):
    log('$', ' '.join(cmd if len(' '.join(cmd)) < 400 else cmd[:6] + ['...']))
    subprocess.run(cmd, check=True, **kw)


def fresh(out, *inputs):
    """True if out exists and is newer than every input."""
    if not os.path.exists(out):
        return False
    t = os.path.getmtime(out)
    return all(os.path.getmtime(i) <= t for i in inputs if os.path.exists(i))


# ---------------------------------------------------------------- classpath

def version_libraries(version):
    path = os.path.join(CONFIG['INSTALL'], 'versions', version, version + '.json')
    with open(path) as f:
        d = json.load(f)
    out = []
    if d.get('inheritsFrom'):
        out += version_libraries(d['inheritsFrom'])
    for l in d.get('libraries', []):
        rules = l.get('rules')
        if rules and not any(r.get('action') == 'allow' and r.get('os', {}).get('name') in (None, 'windows', 'linux')
                             for r in rules):
            continue
        art = l.get('downloads', {}).get('artifact')
        if not art or not art.get('path'):
            continue
        if ':natives-' in l['name']:
            continue
        p = lib(art['path'])
        if os.path.exists(p):
            out.append(p)
    return out


def game_libraries():
    """Every library jar the NeoForge client launches with, minus the game itself."""
    libs = []
    seen = set()
    for p in version_libraries('neoforge-' + CONFIG['NEOFORGE']):
        key = re.sub(r'/[^/]+/[^/]+$', '', p)  # group/artifact dir: keep newest only once
        if p in seen:
            continue
        seen.add(p)
        libs.append(p)
    # Drop the Minecraft/NeoForge jars themselves; they are handled separately.
    libs = [p for p in libs if '/net/minecraft/client/' not in p and '/net/neoforged/neoforge/' not in p]
    return libs


# ------------------------------------------------------------------ prepare

def stage_prepare():
    os.makedirs(W(), exist_ok=True)
    import mappings as M

    mojtxt = lib('net', 'minecraft', 'client', CONFIG['NEOFORM'], f'client-{CONFIG["NEOFORM"]}-mappings.txt')
    mapdir = W('map')
    if not fresh(os.path.join(mapdir, 'yarn2moj.tsrg'), mojtxt, CONFIG['INTERMEDIARY'],
                 os.path.join(HERE, 'tools', 'mappings.py')):
        sys.argv = ['mappings.py', mojtxt, CONFIG['INTERMEDIARY'], CONFIG['YARN'], mapdir]
        M.main()

    # Minecraft with NeoForge's binary patches applied, Mojang names.
    srg = lib('net', 'minecraft', 'client', CONFIG['NEOFORM'], f'client-{CONFIG["NEOFORM"]}-srg.jar')
    patched = lib('net', 'neoforged', 'neoforge', CONFIG['NEOFORGE'], f'neoforge-{CONFIG["NEOFORGE"]}-client.jar')
    universal = lib('net', 'neoforged', 'neoforge', CONFIG['NEOFORGE'], f'neoforge-{CONFIG["NEOFORGE"]}-universal.jar')
    mc_moj = W('mc-moj.jar')
    if not fresh(mc_moj, srg, patched):
        log('merging vanilla + NeoForge-patched classes')
        with zipfile.ZipFile(patched) as pz:
            patched_names = set(n for n in pz.namelist() if n.endswith('.class'))
        with zipfile.ZipFile(mc_moj + '.tmp', 'w', zipfile.ZIP_DEFLATED) as out:
            with zipfile.ZipFile(patched) as pz:
                for n in pz.namelist():
                    if n.endswith('.class'):
                        out.writestr(n, pz.read(n))
            with zipfile.ZipFile(srg) as sz:
                for n in sz.namelist():
                    if n.endswith('.class') and n not in patched_names:
                        out.writestr(n, sz.read(n))
        os.replace(mc_moj + '.tmp', mc_moj)

    libs = game_libraries()
    with open(W('libraries.txt'), 'w') as f:
        f.write('\n'.join(libs) + '\n')

    # Unpack jar-in-jar libraries NeoForge ships (MixinExtras).
    with zipfile.ZipFile(universal) as uz:
        for n in uz.namelist():
            if n.startswith('META-INF/jarjar/') and n.endswith('.jar') and 'mixinextras' in n:
                dst = W('jarjar', os.path.basename(n))
                if not os.path.exists(dst):
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    with open(dst, 'wb') as f:
                        f.write(uz.read(n))

    art = lib('net', 'neoforged', 'AutoRenamingTool', '2.0.3', 'AutoRenamingTool-2.0.3-all.jar')
    tsrg = os.path.join(mapdir, 'moj2yarn.tsrg')
    mc_yarn = W('mc-yarn.jar')
    if not fresh(mc_yarn, mc_moj, tsrg):
        cmd = [java(), '-Xmx2g', '-jar', art, '--input', mc_moj, '--output', mc_yarn,
               '--names', tsrg, '--threads', '2', '--log', W('art-mc.log')]
        for l in libs:
            cmd += ['--lib', l]
        run(cmd)
    nf_yarn = W('neoforge-yarn.jar')
    if not fresh(nf_yarn, universal, tsrg):
        cmd = [java(), '-Xmx2g', '-jar', art, '--input', universal, '--output', nf_yarn,
               '--names', tsrg, '--threads', '2', '--lib', mc_moj, '--log', W('art-nf.log')]
        for l in libs:
            cmd += ['--lib', l]
        run(cmd)
    log('prepare done')


STAGES = {
    'prepare': stage_prepare,
}

if __name__ == '__main__':
    for s in sys.argv[1:] or ['prepare']:
        STAGES[s]()
