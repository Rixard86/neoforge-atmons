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
import glob
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


def run(cmd, quiet=None, **kw):
    log('$', ' '.join(cmd if len(' '.join(cmd)) < 400 else cmd[:6] + ['...']))
    if quiet:
        with open(quiet, 'w') as out:
            r = subprocess.run(cmd, stdout=out, stderr=subprocess.STDOUT, **kw)
        if r.returncode:
            with open(quiet) as f:
                tail = f.read()[-4000:]
            raise SystemExit(f'command failed ({r.returncode}), log {quiet}:\n{tail}')
        return
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

    mojtxt = lib('net', 'minecraft', 'client', CONFIG['NEOFORM'], f'client-{CONFIG["NEOFORM"]}-mappings.txt')
    mapdir = W('map')
    if not fresh(os.path.join(mapdir, 'moj2yarn-declared.tsrg'), mojtxt, CONFIG['INTERMEDIARY'],
                 os.path.join(HERE, 'tools', 'mappings.py')):
        sys.argv = ['mappings.py', mojtxt, CONFIG['INTERMEDIARY'], CONFIG['YARN'], mapdir]
        M.main()

    prop_tool = os.path.join(HERE, 'tools', 'java', 'PropagateMappings.java')
    if not fresh(os.path.join(mapdir, 'yarn2moj.tsrg'), os.path.join(mapdir, 'moj2yarn-declared.tsrg'), mc_moj, prop_tool):
        run([java(), '-Xmx1500m', '-cp', os.pathsep.join(asm_jars()), prop_tool,
             os.path.join(mapdir, 'moj2yarn-declared.tsrg'), mc_moj,
             os.path.join(mapdir, 'moj2yarn.tsrg'), os.path.join(mapdir, 'yarn2moj.tsrg'),
             os.path.join(mapdir, 'proposals.txt')])

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
               '--names', tsrg, '--threads', '2']
        for l in libs:
            cmd += ['--lib', l]
        run(cmd, quiet=W('art-mc.log'))
    nf_yarn = W('neoforge-yarn.jar')
    if not fresh(nf_yarn, universal, tsrg):
        cmd = [java(), '-Xmx2g', '-jar', art, '--input', universal, '--output', nf_yarn,
               '--names', tsrg, '--threads', '2', '--lib', mc_moj]
        for l in libs:
            cmd += ['--lib', l]
        run(cmd, quiet=W('art-nf.log'))
    log('prepare done')


# ------------------------------------------------------------------ compile

def asm_jars():
    return [lib('org', 'ow2', 'asm', a, '9.10.1', f'{a}-9.10.1.jar')
            for a in ('asm', 'asm-tree', 'asm-commons', 'asm-util', 'asm-analysis')]


def mixin_jar():
    return lib('net', 'fabricmc', 'sponge-mixin', '0.15.2+mixin.0.8.7', 'sponge-mixin-0.15.2+mixin.0.8.7.jar')


# Upstream mixins not built for NeoForge. Their class names are also removed
# from the NeoForge mixin config.
#   WallBlockMixin: connects walls to the Copper Beacon, which is server content.
#   WeightedBakedModelMixin, MultipartBakedModelMixin: NeoForge replaces the
#     vanilla getQuads they target; ported in compat/ on NeoForge's overload.
EXCLUDED_MIXINS = ['WallBlockMixin', 'WeightedBakedModelMixin', 'MultipartBakedModelMixin']


def patched_upstream_sources():
    """src/main/java with neoforge/patches applied (NeoForge-only changes to
    upstream files, kept as patches so the tree itself stays upstream)."""
    root = W('patched')
    if os.path.exists(root):
        shutil.rmtree(root)
    shutil.copytree(os.path.join(REPO, 'src', 'main', 'java'), os.path.join(root, 'src', 'main', 'java'))
    patches = sorted(glob.glob(os.path.join(HERE, 'patches', '*.patch')))
    for p in patches:
        run(['git', 'apply', '--whitespace=nowarn', '-p1', p], cwd=root)
    log(f'applied {len(patches)} NeoForge patches')
    return os.path.join(root, 'src', 'main', 'java')


def source_files(upstream_java):
    roots = [upstream_java,
             os.path.join(REPO, 'rtxremix-java', 'src'),
             os.path.join(HERE, 'compat', 'java'),
             os.path.join(HERE, 'compat', 'compileonly')]
    mixin_dir = os.path.join(upstream_java, 'com', 'nvidia', 'rtxremix', 'minecraft', 'mixin')
    excluded = {os.path.join(mixin_dir, m + '.java') for m in EXCLUDED_MIXINS}
    out = []
    for r in roots:
        for dp, _, fs in os.walk(r):
            out += [os.path.join(dp, f) for f in fs if f.endswith('.java')]
    return sorted(p for p in out if p not in excluded)


def access_wideners():
    """Upstream's widener plus the NeoForge port's (what Fabric API's
    transitive wideners provided on Fabric). Both apply at compile time and,
    as an access transformer, at runtime."""
    return [os.path.join(REPO, 'src', 'main', 'resources', 'rtx-remix-renderer.accesswidener'),
            os.path.join(HERE, 'compat', 'neoforge.accesswidener')]


def compile_classpath():
    cp = [W('mc-yarn-aw.jar'), W('neoforge-yarn.jar')]
    cp += [os.path.join(W('jarjar'), f) for f in sorted(os.listdir(W('jarjar')))]
    with open(W('libraries.txt')) as f:
        cp += [l.strip() for l in f if l.strip()]
    return cp


def stage_compile():
    aws = access_wideners()
    tool = os.path.join(HERE, 'tools', 'java', 'AccessWidenerApplier.java')
    if not fresh(W('mc-yarn-aw.jar'), W('mc-yarn.jar'), tool, *aws):
        run([java(), '-cp', os.pathsep.join(asm_jars()), tool, W('mc-yarn.jar'), W('mc-yarn-aw.jar')] + aws)

    out = W('classes')
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)
    srcs = source_files(patched_upstream_sources())
    with open(W('sources.txt'), 'w') as f:
        f.write('\n'.join(srcs) + '\n')
    cp = compile_classpath()
    # MixinExtras first on the processor path: its processor registers the
    # MixinExtras injectors (@WrapOperation, @ModifyExpressionValue, ...) with
    # Mixin's, which must happen before Mixin's processor reports the
    # annotation types it handles, or those injectors get no refmap entries.
    ap = [p for p in cp if 'mixinextras' in p] + [mixin_jar()] + asm_jars() + \
        [p for p in cp if '/gson/' in p or '/guava/guava' in p or 'failureaccess' in p]
    cmd = [java('javac'), '-J-Xmx2200m', '-encoding', 'UTF-8', '--release', '21', '-nowarn',
           '-Xmaxerrs', '5000', '-Xlint:-options', '-parameters',
           '-d', out, '-cp', os.pathsep.join(cp),
           '-processorpath', os.pathsep.join(ap),
           '-AreobfTsrgFile=' + W('map', 'yarn2moj.tsrg'),
           '-AoutTsrgFile=' + W('mixin-out.tsrg'),
           '-AoutRefMapFile=' + os.path.join(out, 'rtx-remix-renderer-refmap.json'),
           '-AdefaultObfuscationEnv=searge', '-AmappingTypes=tsrg',
           '@' + W('sources.txt')]
    run(cmd, quiet=W('javac.log'))
    log('compile done:', sum(len(fs) for _, _, fs in os.walk(out)), 'files')


# ------------------------------------------------------------------ package

PORT_VERSION = '6'
RELOCATE_FROM = 'net/fabricmc/'
RELOCATE_TO = 'com/nvidia/rtxremix/neoforge/fabric/'
UPSTREAM_MIXIN_CONFIG = 'rtx-remix-renderer.mixins.json'
COMPAT_MIXIN_CONFIG = 'rtx_remix_renderer.neoforge.mixins.json'
REFMAP = 'rtx-remix-renderer-refmap.json'
# Release-jar entries not carried into the NeoForge jar.
DROP_FROM_RELEASE = ['*.class', 'fabric.mod.json', REFMAP, 'rtx-remix-renderer.accesswidener',
                     UPSTREAM_MIXIN_CONFIG, 'META-INF/MANIFEST.MF',
                     'data/*',                       # Copper Beacon recipe, loot table and tags
                     'native/remix-sdk-arm64/*']     # Windows on ARM runtime (this build is x64)


def mod_version():
    base = os.path.basename(CONFIG['RELEASE_JAR'])[len('rtx-remix-renderer-'):-len('.jar')]
    return f'{base}-neoforge.{PORT_VERSION}'


def final_mapping():
    """yarn -> mojmap for the compiled classes: the game mapping, the mixin
    members the annotation processor resolved, and the compat relocation."""
    out = W('map', 'final.tsrg')
    lines = []
    with open(W('map', 'yarn2moj.tsrg'), encoding='utf-8') as f:
        lines.append(f.read().rstrip('\n'))
    blocks = {}
    with open(W('mixin-out.tsrg'), encoding='utf-8') as f:
        for l in f:
            p = l.split()
            if len(p) == 3:
                blocks.setdefault(p[0], []).append(f'\t{p[1]} {p[2]}')
            elif len(p) == 4:
                blocks.setdefault(p[0], []).append(f'\t{p[1]} {p[2]} {p[3]}')
    classes_dir = W('classes')
    relocated = []
    for dp, _, fs in os.walk(os.path.join(classes_dir, *RELOCATE_FROM.strip('/').split('/'))):
        for fn in fs:
            if fn.endswith('.class'):
                rel = os.path.relpath(os.path.join(dp, fn), classes_dir).replace(os.sep, '/')[:-6]
                relocated.append(rel)
    for c in sorted(set(relocated) | set(blocks)):
        target = RELOCATE_TO + c[len(RELOCATE_FROM):] if c.startswith(RELOCATE_FROM) else c
        lines.append(f'{c} {target}')
        lines.extend(blocks.get(c, []))
    with open(out, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    return out


def mods_toml(version):
    with open(os.path.join(HERE, 'neoforge.mods.toml')) as f:
        return f.read().replace('${version}', version).replace('${mod_id}', MOD_ID) \
            .replace('${upstream_mixins}', UPSTREAM_MIXIN_CONFIG).replace('${compat_mixins}', COMPAT_MIXIN_CONFIG)


def mixin_configs():
    with open(os.path.join(REPO, 'src', 'main', 'resources', UPSTREAM_MIXIN_CONFIG)) as f:
        upstream = json.load(f)
    for section in ('mixins', 'client', 'server'):
        if section in upstream:
            upstream[section] = [m for m in upstream[section] if m not in EXCLUDED_MIXINS]
    upstream['refmap'] = REFMAP
    compat_dir = os.path.join(HERE, 'compat', 'java', 'com', 'nvidia', 'rtxremix', 'neoforge', 'mixin')
    compat = sorted(f[:-5] for f in os.listdir(compat_dir) if f.endswith('.java'))
    compat_cfg = {
        'required': True,
        'minVersion': '0.8',
        'package': 'com.nvidia.rtxremix.neoforge.mixin',
        'compatibilityLevel': 'JAVA_21',
        'refmap': REFMAP,
        'client': compat,
        'injectors': {'defaultRequire': 1},
    }
    return upstream, compat_cfg


def stage_package():
    from tsrg import Tsrg, aw_to_at
    version = mod_version()
    stage = W('stage')
    if os.path.exists(stage):
        shutil.rmtree(stage)
    os.makedirs(stage)

    # Compiled classes (Yarn names) -> Mojang names, with the compat relocation.
    classes = W('classes')
    yarn_jar = W('mod-yarn.jar')
    with zipfile.ZipFile(yarn_jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for dp, _, fs in os.walk(classes):
            for fn in fs:
                full = os.path.join(dp, fn)
                rel = os.path.relpath(full, classes).replace(os.sep, '/')
                if rel.startswith('org/jetbrains/') or not rel.endswith('.class'):
                    continue
                z.write(full, rel)
    mapping = final_mapping()
    art = lib('net', 'neoforged', 'AutoRenamingTool', '2.0.3', 'AutoRenamingTool-2.0.3-all.jar')
    moj_jar = W('mod-moj.jar')
    cmd = [java(), '-Xmx1500m', '-jar', art, '--input', yarn_jar, '--output', moj_jar,
           '--names', mapping, '--threads', '2', '--lib', W('mc-yarn.jar'), '--lib', W('neoforge-yarn.jar')]
    for l in compile_classpath()[2:]:
        cmd += ['--lib', l]
    run(cmd, quiet=W('art-mod.log'))
    with zipfile.ZipFile(moj_jar) as z:
        z.extractall(stage)

    # Generated metadata and configs.
    os.makedirs(os.path.join(stage, 'META-INF'), exist_ok=True)
    with open(os.path.join(stage, 'META-INF', 'neoforge.mods.toml'), 'w') as f:
        f.write(mods_toml(version))
    at = aw_to_at(access_wideners(), Tsrg(W('map', 'yarn2moj.tsrg')))
    with open(os.path.join(stage, 'META-INF', 'accesstransformer.cfg'), 'w') as f:
        f.write('# Generated from the Fabric access wideners (Yarn) by neoforge/build.py\n')
        f.write('\n'.join(at) + '\n')
    upstream_cfg, compat_cfg = mixin_configs()
    with open(os.path.join(stage, UPSTREAM_MIXIN_CONFIG), 'w') as f:
        json.dump(upstream_cfg, f, indent=4)
    with open(os.path.join(stage, COMPAT_MIXIN_CONFIG), 'w') as f:
        json.dump(compat_cfg, f, indent=4)
    shutil.copy(os.path.join(classes, REFMAP), os.path.join(stage, REFMAP))
    with open(os.path.join(stage, 'pack.mcmeta'), 'w') as f:
        json.dump({'pack': {'description': 'RTX Remix for Minecraft resources', 'pack_format': 34}}, f)
    with open(os.path.join(stage, 'META-INF', 'MANIFEST.MF'), 'w') as f:
        f.write('Manifest-Version: 1.0\n'
                f'Automatic-Module-Name: {MOD_ID}\n'
                f'Implementation-Title: {MOD_ID}\n'
                f'Implementation-Version: {version}\n'
                'Implementation-Vendor: NVIDIA (upstream), NeoForge port\n\n')
    shutil.copy(os.path.join(HERE, 'NOTICE-neoforge.txt'), os.path.join(stage, 'NOTICE-neoforge.txt'))

    # Start from NVIDIA's release jar (native runtime, textures, assets) and
    # swap in the NeoForge build. zip edits in place without recompressing.
    dist = W('dist')
    os.makedirs(dist, exist_ok=True)
    final = os.path.join(dist, f'rtx-remix-renderer-{version}.jar')
    shutil.copy(CONFIG['RELEASE_JAR'], final + '.tmp')
    subprocess.run(['zip', '-q', '-d', final + '.tmp'] + DROP_FROM_RELEASE, check=False)
    subprocess.run(['zip', '-q', '-r', os.path.abspath(final + '.tmp'), '.'], cwd=stage, check=True)
    os.replace(final + '.tmp', final)
    log('package done:', final, f'{os.path.getsize(final) / 1e6:.0f} MB')


# ------------------------------------------------------------------- verify

def built_jar():
    return os.path.join(W('dist'), f'rtx-remix-renderer-{mod_version()}.jar')


def stage_verify():
    """Static check of the built jar against the real runtime (Mojang-named,
    NeoForge-patched Minecraft, NeoForge, libraries) and the instance's mods."""
    universal = lib('net', 'neoforged', 'neoforge', CONFIG['NEOFORGE'], f'neoforge-{CONFIG["NEOFORGE"]}-universal.jar')
    runtime = [W('mc-moj.jar'), universal] + compile_classpath()[2:]
    with open(W('runtime-classpath.txt'), 'w') as f:
        f.write('\n'.join(runtime) + '\n')
    tool = os.path.join(HERE, 'tools', 'java', 'VerifyPort.java')
    cmd = [java(), '-Xmx1500m', '-cp', os.pathsep.join(asm_jars()), tool, built_jar(), W('runtime-classpath.txt')]
    mods = os.environ.get('RTX_INSTANCE_MODS')
    if mods:
        cmd.append(mods)
    log('$', 'VerifyPort', built_jar())
    with open(W('verify.log'), 'w') as out:
        r = subprocess.run(cmd, stdout=out, stderr=subprocess.STDOUT)
    with open(W('verify.log')) as f:
        text = f.read()
    print(text[-6000:])
    if r.returncode:
        raise SystemExit('verify failed, see ' + W('verify.log'))


STAGES = {
    'prepare': stage_prepare,
    'compile': stage_compile,
    'package': stage_package,
    'verify': stage_verify,
}

if __name__ == '__main__':
    for s in sys.argv[1:] or ['prepare']:
        STAGES[s]()
