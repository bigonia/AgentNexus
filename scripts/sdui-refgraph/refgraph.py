"""sdui 旧协议簇引用图分析（P5c 前置）。

目的：把 §7「待清除模块清单」从粗估变成经引用图验证的可执行清单。

判定规则（从"假设全删"向下收敛到最大可删集）：
  f 可删 <=> f 的所有引用者也都可删         （引用者留在主代码就编译不过）
        且 f 没有实现"删除集外的工程内契约" （否则该契约会失去实现）
保留方 -> 可删集 的类型依赖 = P5c 必须逐条处理的改造点。
"""
import os
import re
import json
from collections import defaultdict

MAIN = 'src/main/java'
TEST = 'src/test/java'
BASE = 'com.zwbd.agentnexus'
# 关键：这里必须允许通配导入末尾的 '*'，否则 `import ...service.*;` 会被整条漏掉
IMPORT_RE = re.compile(r'^\s*import\s+(?:static\s+)?([\w\.]+(?:\.\*)?)\s*;', re.M)
PKG_RE = re.compile(r'^\s*package\s+([\w\.]+)\s*;', re.M)
DECL_RE = re.compile(r'\b(?:implements|extends)\s+([^\{;]+)')


def scan(root):
    fqcn_to_path, pkg_members = {}, defaultdict(dict)
    for dirpath, _, names in os.walk(root):
        for n in sorted(names):
            if not n.endswith('.java'):
                continue
            p = os.path.join(dirpath, n)
            rel = os.path.relpath(p, root).replace('\\', '/')
            fqcn = rel[:-5].replace('/', '.')
            fqcn_to_path[fqcn] = p
            pkg_members[fqcn.rsplit('.', 1)[0]][n[:-5]] = fqcn
    return fqcn_to_path, pkg_members


main_path, main_pkgs = scan(MAIN)
test_path, test_pkgs = scan(TEST)
all_path = dict(main_path, **{k: v for k, v in test_path.items() if k not in main_path})

# 工程内简单名 -> fqcn（跨包同名取先出现者，够用于契约识别）
simple_to_fqcn = {}
for fq in all_path:
    simple_to_fqcn.setdefault(fq.rsplit('.', 1)[1], fq)


def parse(root, pkgs, path_map):
    info = {}
    for fqcn, path in path_map.items():
        with open(path, 'r', encoding='utf-8', errors='ignore') as fh:
            text = fh.read()
        m = PKG_RE.search(text)
        pkg = m.group(1) if m else ''
        imports, wildcards = set(), set()
        for im in IMPORT_RE.finditer(text):
            imp = im.group(1)
            if imp.endswith('.*'):
                wildcards.add(imp[:-2])
            else:
                imports.add(imp)
        body = IMPORT_RE.sub('', PKG_RE.sub('', text))
        body = re.sub(r'/\*.*?\*/', ' ', body, flags=re.S)
        body = re.sub(r'//[^\n]*', ' ', body)
        idents = set(re.findall(r'\b[A-Z][A-Za-z0-9_]*\b', body))
        info[fqcn] = {'path': path, 'pkg': pkg, 'imports': imports,
                      'wildcards': wildcards, 'idents': idents, 'body': body}
    return info


info = parse(MAIN, main_pkgs, main_path)
pkg_index = defaultdict(dict)
for fq in main_path:
    pkg_index[fq.rsplit('.', 1)[0]][fq.rsplit('.', 1)[1]] = fq


def refs_of(fqcn):
    i = info[fqcn]
    out = {imp for imp in i['imports'] if imp.startswith(BASE)}
    for wp in i['wildcards']:
        if wp.startswith(BASE):
            out |= {t for s, t in pkg_index.get(wp, {}).items() if s in i['idents']}
    out |= {t for s, t in pkg_index.get(i['pkg'], {}).items()
            if t != fqcn and s in i['idents']}
    out.discard(fqcn)
    return {t for t in out if t in all_path}


def supertypes(fqcn):
    """类声明里 implements/extends 的工程内类型（外层类名）。"""
    out = set()
    for m in DECL_RE.finditer(info[fqcn]['body']):
        for part in re.split(r'[,\s]+', m.group(1).strip()):
            part = re.sub(r'<.*', '', part).strip()
            outer = part.split('.')[0]
            if outer in simple_to_fqcn:
                out.add(simple_to_fqcn[outer])
    return out


refs = {f: refs_of(f) for f in info}
rev = defaultdict(set)
for f, ts in refs.items():
    for t in ts:
        rev[t].add(f)

SDUI = BASE + '.sdui'
V2 = SDUI + '.v2'
non_v2 = {f for f in main_path if f.startswith(SDUI + '.')
          and not (f == V2 or f.startswith(V2 + '.'))}
v2 = {f for f in main_path if f == V2 or f.startswith(V2 + '.')}
outside = {f for f in main_path if not f.startswith(SDUI + '.')}

KEEP_PKG = (V2, SDUI + '.ui', SDUI + '.workflow', SDUI + '.artifact',
            SDUI + '.repo', SDUI + '.model', SDUI + '.dto',
            SDUI + '.controller', SDUI + '.debug', SDUI + '.resources')


def in_keep(f):
    return any(f == p or f.startswith(p + '.') for p in KEEP_PKG)


def kind(f):
    if any(f == p or f.startswith(p + '.') for p in
           (V2, SDUI + '.ui', SDUI + '.workflow', SDUI + '.artifact',
            SDUI + '.repo', SDUI + '.model', SDUI + '.dto', SDUI + '.resources')):
        return 'ui'
    if f.startswith(SDUI + '.controller'):
        return 'ctrl'
    if f.startswith(SDUI + '.debug'):
        return 'dbg'
    return 'pool'


KEEP = {f for f in main_path if in_keep(f) or f in outside}
POOL = {f for f in non_v2 if not in_keep(f)}
sup_cache = {f: supertypes(f) for f in non_v2}


def converge(pool, keep):
    """从"假设全删"向下收敛到最大可删集。

    两条必备约束：
      1. 引用者必须也在删除集内（否则留下编译不过的调用方）；
      2. 不能实现删除集之外的工程内契约（否则契约失去实现）——
         Spring 按类型注入的实现类天然零显式引用者，只靠"零引用"会误杀。
    """
    del_set = set(pool)
    while True:
        drop = {f for f in del_set
                if (rev[f] - del_set) or any(s not in del_set for s in sup_cache[f])}
        if not drop:
            return del_set
        del_set -= drop


DEL = converge(POOL, KEEP)

print('=== 规模 ===')
print(f'  非 v2 主代码: {len(non_v2)}   v2: {len(v2)}   保留包: {len(KEEP & non_v2)}   sdui 外: {len(outside)}')
print(f'  候选池: {len(POOL)}')
print(f'  ★ A 类 可整文件删: {len(DEL)}')
print(f'  ★ C 类 留池不可删: {len(POOL - DEL)}')

by_pkg = defaultdict(list)
for t in DEL:
    by_pkg[t.rsplit('.', 1)[0].replace(SDUI + '.', '')].append(t.rsplit('.', 1)[1])
print(f'\n=== 硬边界：v2 直接引用的非 v2 类 ===')
hb = defaultdict(set)
for f in v2:
    for t in refs[f]:
        if t in non_v2:
            hb[t].add(f)
for t, users in sorted(hb.items()):
    print(f'  {t.replace(SDUI + ".", ""):44s} <- '
          + ', '.join(u.rsplit('.', 1)[1] for u in sorted(users)))
print(f'  共 {len(hb)} 个，P5c 必须保留')
out_ref = {t for f in outside for t in refs[f] if t in non_v2}
print(f'\n=== sdui 之外的主代码引用的 sdui 非 v2 类: {len(out_ref)} ===')
for t in sorted(out_ref):
    print(f'  {t.replace(SDUI + ".", "")}')

print(f'\n=== A 类：可整文件删除（{len(DEL)}）===')
for pkg in sorted(by_pkg):
    print(f'\n[{pkg or "(root)"}] {len(by_pkg[pkg])}')
    for n in sorted(by_pkg[pkg]):
        print(f'     {n}')

print(f'\n=== B 类：保留方 -> A 类的类型依赖（P5c 改造点）===')
edges = defaultdict(set)
for f in sorted(KEEP | (POOL - DEL)):
    src = f if f in KEEP else f
    for t in refs[f]:
        if t in DEL:
            edges[src].add(t)
for f in sorted(edges):
    tag = 'v2 ' if f in v2 else ('out' if f in outside else 'pkg')
    print(f'  [{tag}] {f.replace(SDUI + ".", ""):52s} -> '
          + ', '.join(sorted(t.rsplit(".", 1)[1] for t in edges[f])))
used = {t for ts in edges.values() for t in ts}
print(f'  小计 {len(edges)} 个保留方指向 {len(used)} 个待删类')

print(f'\n=== C 类：需先改造引用方才能删（{len(POOL - DEL)}）===')
for f in sorted(POOL - DEL):
    by_kind = defaultdict(list)
    for b in sorted(rev[f]):
        by_kind[kind(b)].append(b.replace(SDUI + '.', ''))
    if not by_kind:
        desc = '(零引用者：Spring 装配点)'
    else:
        desc = '  '.join(f'{k}:{len(v)}' for k, v in sorted(by_kind.items()))
    flags = ' '.join(k for k in by_kind)
    print(f'  {f.replace(SDUI + ".", ""):48s} [{flags:9s}] {desc[:60]}')

# 受影响测试
test_refs = {}
for t in test_path:
    with open(test_path[t], 'r', encoding='utf-8', errors='ignore') as fh:
        text = fh.read()
    hits = {m for m in re.findall(r'import\s+(?:static\s+)?([\w\.]+)', text)
            if m in DEL}
    if hits:
        test_refs[t] = hits
print(f'\n=== 释放容量：各保留方阻塞了多少待删类（P5c 执行顺序依据）===')
stuck = POOL - DEL
releasers = defaultdict(set)
for t in stuck:
    for b in rev[t]:
        if kind(b) in ('ctrl', 'dbg'):
            releasers[b].add(t)
for b in sorted(releasers, key=lambda x: -len(releasers[x])):
    print(f'  [{kind(b)}] {b.replace(SDUI + ".", ""):42s} 阻塞 {len(releasers[b]):2d} 个')

print(f'\n=== 被 ui / workflow / v2 阻塞的类（这些是真依赖，不能删）===')
for t in sorted(stuck):
    ui_refs = [b for b in rev[t] if kind(b) == 'ui']
    ctrl_refs = [b for b in rev[t] if kind(b) in ('ctrl', 'dbg')]
    if ui_refs and not ctrl_refs:
        print(f'  {t.replace(SDUI + ".", ""):48s} <- '
              + ', '.join(b.replace(SDUI + '.', '') for b in sorted(ui_refs))[:60])

print(f'\n=== 受影响测试文件：{len(test_refs)} ===')
for t in sorted(test_refs):
    print(f'  {t.replace(BASE + ".", ""):56s} -> '
          + ', '.join(sorted(h.rsplit(".", 1)[1] for h in test_refs[t]))[:56])

# ---- 推演：控制层裁剪完成后的可删集 ----
KEEP2 = {f for f in KEEP if kind(f) not in ('ctrl', 'dbg')}
POOL2 = non_v2 - KEEP2
DEL2 = converge(POOL2, KEEP2)
print(f'\n=== 推演：控制层（controller / debug）裁剪完成后 ===')
print(f'  可整文件删: {len(DEL2)}（当前 {len(DEL)}，增加 {len(DEL2) - len(DEL)}）')
print('  注意：其中含被 HTTP 暴露的管理端点，删除前需业务确认（脚本无法判断端点是否在用）')
extra = sorted(DEL2 - DEL)
by_pkg2 = defaultdict(list)
for t in extra:
    by_pkg2[t.rsplit('.', 1)[0].replace(SDUI + '.', '')].append(t.rsplit('.', 1)[1])
for pkg in sorted(by_pkg2):
    print(f'\n[{pkg or "(root)"}] {len(by_pkg2[pkg])}')
    for n in sorted(by_pkg2[pkg]):
        print(f'     {n}')
still = sorted(POOL2 - DEL2)
print(f'\n  推演后仍保留在池中（引用方为 ui / v2，属真实依赖）: {len(still)}')
for f in still:
    print(f'     {f.replace(SDUI + ".", "")}')

with open('target/refgraph.json', 'w', encoding='utf-8') as fh:
    json.dump({'deletable': sorted(DEL),
               'keep_to_del': {k: sorted(v) for k, v in edges.items()},
               'undecided': sorted(POOL - DEL),
               'tests_to_fix': {k: sorted(v) for k, v in test_refs.items()}},
              fh, ensure_ascii=False, indent=1)
print('\n写出 target/refgraph.json')
