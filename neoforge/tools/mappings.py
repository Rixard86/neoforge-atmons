#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compose Mojang (NeoForge runtime) <-> Yarn mappings for Minecraft 1.21.1.

NeoForge 21.1 runs Minecraft with Mojang's official names. The upstream mod is
written against Yarn. This script joins the three mapping sets that are
available offline into one direct table:

    mojmap --(Mojang proguard txt)--> official --(intermediary tiny)--> intermediary --(yarn enigma)--> yarn

and writes TSRG v1 files in both directions:

    moj2yarn-declared.tsrg   left = mojmap, right = yarn, members as declared

tools/java/PropagateMappings.java then propagates names through the class
hierarchy (overrides must share a name) and writes moj2yarn.tsrg/yarn2moj.tsrg.

Usage: mappings.py <mojang-mappings.txt> <intermediary.tiny> <yarn-mappings-dir> <out-dir>
"""
import os
import re
import sys

PRIMS = {
    'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S',
    'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D',
}
DESC_CLASS = re.compile(r'L([^;]+);')


def remap_desc(desc, cmap):
    return DESC_CLASS.sub(lambda m: 'L' + cmap.get(m.group(1), m.group(1)) + ';', desc)


def java_type_to_desc(t):
    dims = 0
    while t.endswith('[]'):
        dims += 1
        t = t[:-2]
    d = PRIMS.get(t) or ('L' + t.replace('.', '/') + ';')
    return '[' * dims + d


def read_proguard(path):
    """Returns (classes, fields, methods) in mojmap terms, keyed by official names.

    classes: off -> moj
    fields:  (offOwner, offName) -> mojName
    methods: (offOwner, offName, mojDescWithMojTypes) -> mojName  (desc fixed up later)
    """
    classes = {}
    fields = []
    methods = []
    cur_moj = cur_off = None
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            if not line[0].isspace():
                moj, off = line.rstrip().rstrip(':').split(' -> ')
                cur_moj = moj.replace('.', '/')
                cur_off = off.replace('.', '/')
                classes[cur_off] = cur_moj
                continue
            body, obf = line.strip().split(' -> ')
            if '(' in body:
                body = re.sub(r'^\d+:\d+:', '', body)
                body = re.sub(r':\d+:\d+$', '', body)
                ret, rest = body.split(' ', 1)
                name, args = rest.split('(', 1)
                args = args.rstrip(')')
                argd = ''.join(java_type_to_desc(a) for a in args.split(',') if a)
                methods.append((cur_off, obf, name, '(' + argd + ')' + java_type_to_desc(ret)))
            else:
                typ, name = body.split(' ')
                fields.append((cur_off, obf, name, java_type_to_desc(typ)))
    return classes, fields, methods


def read_tiny_v1(path):
    classes, fields, methods = {}, {}, {}
    with open(path, encoding='utf-8') as f:
        header = f.readline().split()
        assert header[:3] == ['v1', 'official', 'intermediary'], header
        for line in f:
            p = line.rstrip('\n').split('\t')
            if p[0] == 'CLASS':
                classes[p[1]] = p[2]
            elif p[0] == 'FIELD':
                fields[(p[1], p[3])] = p[4]
            elif p[0] == 'METHOD':
                methods[(p[1], p[3], p[2])] = p[4]
    return classes, fields, methods


def read_yarn(root):
    """Enigma mapping files keyed by intermediary names."""
    classes, fields, methods = {}, {}, {}
    for dirpath, _, files in os.walk(root):
        for fn in files:
            if not fn.endswith('.mapping'):
                continue
            stack = []  # (indent, intermediary full name, named full name)
            with open(os.path.join(dirpath, fn), encoding='utf-8') as f:
                for raw in f:
                    if not raw.strip():
                        continue
                    indent = len(raw) - len(raw.lstrip('\t'))
                    p = raw.strip().split(' ')
                    kind = p[0]
                    if kind in ('ARG', 'COMMENT'):
                        continue
                    while stack and stack[-1][0] >= indent:
                        stack.pop()
                    if kind == 'CLASS':
                        if stack:
                            parent_i, parent_n = stack[-1][1], stack[-1][2]
                            inter = parent_i + '$' + p[1]
                            named = parent_n + '$' + (p[2] if len(p) > 2 else p[1])
                        else:
                            inter = p[1]
                            named = p[2] if len(p) > 2 else p[1]
                        classes[inter] = named
                        stack.append((indent, inter, named))
                    elif kind == 'FIELD' and stack:
                        if len(p) >= 4:
                            fields[(stack[-1][1], p[1])] = p[2]
                    elif kind == 'METHOD' and stack:
                        # METHOD intermediary [named] desc
                        if len(p) >= 4:
                            methods[(stack[-1][1], p[1], p[3])] = p[2]
                        stack.append((indent, stack[-1][1], stack[-1][2]))  # ARG children
    return classes, fields, methods


def main():
    moj_txt, tiny, yarn_dir, out = sys.argv[1:5]
    os.makedirs(out, exist_ok=True)

    off2moj, pg_fields, pg_methods = read_proguard(moj_txt)
    moj2off = {v: k for k, v in off2moj.items()}
    off2int, int_fields, int_methods = read_tiny_v1(tiny)
    int2yarn, y_fields, y_methods = read_yarn(yarn_dir)

    def off2yarn_class(off):
        inter = off2int.get(off, off)
        if inter in int2yarn:
            return int2yarn[inter]
        # Inner classes whose outer is renamed but which have no entry of their own
        if '$' in inter:
            outer, inner = inter.rsplit('$', 1)
            o = off2yarn_class_cache.get(outer)
            if o is None:
                o = int2yarn.get(outer, outer)
            return o + '$' + inner
        return inter

    off2yarn_class_cache = {}
    classes = {}  # moj -> yarn
    for off, moj in off2moj.items():
        y = off2yarn_class(off)
        # Anonymous and local classes: obfuscation renumbers them, so Yarn's
        # numbering can collide with Mojang's. Nothing refers to them by name,
        # so keep Mojang's own suffix under the Yarn outer class.
        if '$' in moj and moj.rsplit('$', 1)[1].isdigit() and '$' in y:
            y = y.rsplit('$', 1)[0] + '$' + moj.rsplit('$', 1)[1]
        classes[moj] = y
    # Outer-class renames must propagate to every nested class consistently.
    for moj in sorted(classes, key=lambda c: c.count('$')):
        if '$' in moj:
            outer_m, inner_m = moj.rsplit('$', 1)
            if outer_m in classes:
                inner_y = classes[moj].rsplit('$', 1)[1] if '$' in classes[moj] else inner_m
                classes[moj] = classes[outer_m] + '$' + inner_y
    seen = {}
    for moj, y in classes.items():
        if y in seen:
            raise SystemExit(f'class mapping collision: {seen[y]} and {moj} -> {y}')
        seen[y] = moj

    # Descriptor helpers per namespace
    yarn_of_moj = classes

    def moj_desc_to_off(d):
        return remap_desc(d, moj2off)

    def off_desc_to_int(d):
        return remap_desc(d, off2int)

    fields = {}  # mojOwner -> list[(moj, yarn)]
    for off_owner, off_name, moj_name, _ in pg_fields:
        inter = int_fields.get((off_owner, off_name))
        int_owner = off2int.get(off_owner, off_owner)
        if inter is None:
            yname = off_name  # not obfuscated: every namespace keeps the official name
        else:
            yname = y_fields.get((int_owner, inter), inter)
        fields.setdefault(off2moj[off_owner], []).append((moj_name, yname))

    methods = {}  # mojOwner -> list[(mojName, mojDesc, yarnName, yarnDesc)]
    n_meth = n_missing = 0
    for off_owner, off_name, moj_name, moj_desc in pg_methods:
        if moj_name in ('<init>', '<clinit>'):
            continue
        off_desc = moj_desc_to_off(moj_desc)
        inter = int_methods.get((off_owner, off_name, off_desc))
        int_owner = off2int.get(off_owner, off_owner)
        if inter is None:
            n_missing += 1
            yname = off_name  # not obfuscated (or inherited from a library type)
        else:
            int_desc = off_desc_to_int(off_desc)
            yname = y_methods.get((int_owner, inter, int_desc), inter)
        ydesc = remap_desc(moj_desc, yarn_of_moj)
        methods.setdefault(off2moj[off_owner], []).append((moj_name, moj_desc, yname, ydesc))
        n_meth += 1

    def write(path, forward):
        with open(path, 'w', encoding='utf-8') as f:
            for moj in sorted(classes):
                y = classes[moj]
                if forward:
                    f.write(f'{moj} {y}\n')
                else:
                    f.write(f'{y} {moj}\n')
                for mn, yn in fields.get(moj, ()):
                    f.write(f'\t{mn} {yn}\n' if forward else f'\t{yn} {mn}\n')
                for mn, md, yn, yd in methods.get(moj, ()):
                    f.write(f'\t{mn} {md} {yn}\n' if forward else f'\t{yn} {yd} {mn}\n')

    # Declared members only; PropagateMappings adds inherited/overriding
    # members from the class hierarchy and writes the final pair of files.
    write(os.path.join(out, 'moj2yarn-declared.tsrg'), True)
    print(f'classes={len(classes)} fields={sum(map(len, fields.values()))} '
          f'methods={n_meth} (no intermediary: {n_missing})')


if __name__ == '__main__':
    main()
