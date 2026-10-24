# SPDX-License-Identifier: Apache-2.0
"""Small helpers over TSRG v1 files: lookup tables and access widener -> access transformer."""
import re

DESC_CLASS = re.compile(r'L([^;]+);')


class Tsrg:
    def __init__(self, path):
        self.classes = {}
        self.fields = {}   # (owner, name) -> name
        self.methods = {}  # (owner, name, desc) -> name
        cur = None
        with open(path, encoding='utf-8') as f:
            for line in f:
                line = line.rstrip('\n')
                if not line:
                    continue
                if line[0] != '\t':
                    a, b = line.split(' ')
                    self.classes[a] = b
                    cur = a
                    continue
                p = line.strip().split(' ')
                if len(p) == 2:
                    self.fields[(cur, p[0])] = p[1]
                else:
                    self.methods[(cur, p[0], p[1])] = p[2]

    def cls(self, name):
        if name in self.classes:
            return self.classes[name]
        if '$' in name:
            outer, inner = name.rsplit('$', 1)
            return self.cls(outer) + '$' + inner
        return name

    def desc(self, d):
        return DESC_CLASS.sub(lambda m: 'L' + self.cls(m.group(1)) + ';', d)


def aw_to_at(widener_paths, mapping):
    """Fabric access widener entries (left namespace of mapping) as NeoForge
    access transformer lines (right namespace)."""
    out = []
    for path in widener_paths:
        with open(path, encoding='utf-8') as f:
            for raw in f:
                line = raw.split('#', 1)[0].strip()
                if not line or line.startswith('accessWidener'):
                    continue
                p = line.split()
                access = p[0].replace('transitive-', '')
                kind = p[1]
                owner = mapping.cls(p[2]).replace('/', '.')
                if kind == 'class':
                    mod = 'public-f' if access == 'extendable' else 'public'
                    out.append(f'{mod} {owner}')
                elif kind == 'field':
                    name = mapping.fields.get((p[2], p[3]), p[3])
                    mod = 'public-f' if access == 'mutable' else 'public'
                    out.append(f'{mod} {owner} {name}')
                elif kind == 'method':
                    name = mapping.methods.get((p[2], p[3], p[4]), p[3])
                    mod = 'public-f' if access == 'extendable' else 'public'
                    out.append(f'{mod} {owner} {name}{mapping.desc(p[4])}')
                else:
                    raise ValueError(raw)
    # Fabric widens the owner (and its outer classes) of any widened member.
    owners = []
    for l in out:
        parts = l.split(' ')
        if len(parts) >= 3:
            o = parts[1]
            while True:
                owners.append(f'public {o}')
                if '$' not in o:
                    break
                o = o.rsplit('$', 1)[0]
    out = out + owners
    # A field made mutable and accessible is one public-f line.
    final = []
    for l in out:
        if l.startswith('public ') and ('public-f ' + l[len('public '):]) in out:
            continue
        if l not in final:
            final.append(l)
    return final
