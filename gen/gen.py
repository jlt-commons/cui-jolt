#!/usr/bin/env python3
"""Generate the Jolt binding for CUI from its C headers.

    python3 gen/gen.py /path/to/CUI

writes src/cui/raw.clj, src/cui/spec.clj and src/cui/core.clj. The headers are
the single source of truth: every struct layout, enum value, icon, function
signature and scope macro comes from them, so regenerating after a CUI update
keeps the binding exact.
"""
import os, re, subprocess, sys
from pycparser import c_ast
sys.path.insert(0, os.path.dirname(__file__))
from model import Model, parse, tstr

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CUI = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, '..', 'CUI'))
INC = os.path.join(CUI, 'include')

# --------------------------------------------------------------------------
# Parse

def preprocess():
    out = os.path.join(HERE, 'all.i')
    subprocess.check_call(['gcc', '-E', '-P', '-undef', '-nostdinc',
                           '-I', os.path.join(HERE, 'stubs'), '-I', INC,
                           '-DCUI_API=', os.path.join(HERE, 'all.h'), '-o', out])
    return out

M = Model(parse(preprocess()))
CORE_NAMES = set(open(os.path.join(HERE, 'core_names.txt')).read().split())

SCALARS = {
    'int': ':int', 'signed int': ':int', 'unsigned int': ':uint', 'unsigned': ':uint',
    'long': ':long', 'unsigned long': ':ulong', 'short': ':int16', 'unsigned short': ':uint16',
    'float': ':float', 'double': ':double', 'char': ':int8', 'signed char': ':int8',
    'unsigned char': ':uint8', '_Bool': ':bool',
    'int8_t': ':int8', 'uint8_t': ':uint8', 'int16_t': ':int16', 'uint16_t': ':uint16',
    'int32_t': ':int32', 'uint32_t': ':uint32', 'int64_t': ':int64', 'uint64_t': ':uint64',
    'size_t': ':size_t', 'uintptr_t': ':uptr', 'intptr_t': ':iptr',
}

def resolve(t):
    """Follow typedef chains on a TypeDecl to its underlying node."""
    seen = 0
    while isinstance(t, c_ast.TypeDecl) and isinstance(t.type, c_ast.IdentifierType):
        n = ' '.join(t.type.names)
        if n in SCALARS or n not in M.typedefs: return t
        t = M.typedefs[n]; seen += 1
        if seen > 20: break
    return t

def ident(t):
    if isinstance(t, c_ast.TypeDecl) and isinstance(t.type, c_ast.IdentifierType):
        return ' '.join(t.type.names)
    return None

def struct_of(t):
    """The struct tag for a by-value struct type node, or None."""
    t = resolve(t)
    if isinstance(t, c_ast.TypeDecl) and isinstance(t.type, c_ast.Struct):
        s = t.type
        name = s.name
        if name in M.structs: return name
    return None

def enum_of(t):
    n = ident(t)
    if n in M.enum_names: return n
    if isinstance(t, c_ast.TypeDecl) and isinstance(t.type, c_ast.Enum):
        return 'enum ' + t.type.name if t.type.name else None
    r = resolve(t)
    if isinstance(r, c_ast.TypeDecl) and isinstance(r.type, c_ast.Enum):
        return n
    return None

def scalar_of(t):
    r = resolve(t)
    n = ident(r)
    return SCALARS.get(n)

def struct_typedef_name(tag):
    """Prefer the typedef name (cui_button_opts) over the tag."""
    for n, t in M.typedefs.items():
        if isinstance(t, c_ast.TypeDecl) and isinstance(t.type, c_ast.Struct) and t.type.name == tag:
            return n
    return tag

TAG_TO_NAME = {tag: struct_typedef_name(tag) for tag in M.structs}

# --------------------------------------------------------------------------
# FFI descriptors (literal, for ffi/layout and [:by-value ...])

def typedef_ptr(t):
    """True for a typedef that names a pointer (cui_paint_fn)."""
    return not isinstance(t, c_ast.PtrDecl) and isinstance(resolve(t), c_ast.PtrDecl)

def ffi_type(t):
    """Literal jolt.ffi type for a field/arg in MEMORY."""
    if isinstance(t, c_ast.PtrDecl) or typedef_ptr(t): return ':pointer'
    if isinstance(t, c_ast.ArrayDecl):
        dim = t.dim.value if isinstance(t.dim, c_ast.Constant) else M.consts[t.dim.name]
        return f'[:array {ffi_type(t.type)} {int(str(dim), 0)}]'
    s = struct_of(t)
    if s: return struct_desc(s)
    if enum_of(t): return ':int'
    sc = scalar_of(t)
    if sc: return sc
    raise ValueError('no ffi type for ' + tstr(t))

def field_list(tag):
    return [(d.name, d.type) for d in M.structs[tag].decls]

def struct_desc(tag):
    fs = ' '.join(f'[:{n} {ffi_type(t)}]' for n, t in field_list(tag))
    return f'[:struct [{fs}]]'

# --------------------------------------------------------------------------
# Marshalling kinds (data the runtime in cui.impl interprets)

def kw(name): return ':' + name

def pointee(t):
    """(const?, inner type node) for a PtrDecl."""
    inner = t.type
    const = isinstance(inner, c_ast.TypeDecl) and 'const' in inner.quals
    return const, inner

def elem_kind(t):
    """Kind of a value stored in memory (used for arrays and pointees)."""
    if typedef_ptr(t): return '[:raw]'
    if isinstance(t, c_ast.PtrDecl):
        const, inner = pointee(t)
        if ident(inner) == 'char': return '[:str]'
        return '[:raw]'
    s = struct_of(t)
    if s: return f'[:struct {kw(TAG_TO_NAME[s])}]'
    e = enum_of(t)
    if e: return f'[:enum {kw(e.replace("enum ", ""))}]'
    if ident(t) == 'cui_font': return '[:font]'
    sc = scalar_of(t)
    if sc == ':bool': return '[:bool]'
    if sc: return f'[:scalar {sc}]'
    return '[:raw]'

def field_kind(name, t):
    if isinstance(t, c_ast.ArrayDecl):
        dim = t.dim.value if isinstance(t.dim, c_ast.Constant) else M.consts[t.dim.name]
        return f'[:array {elem_kind(t.type)} {int(str(dim), 0)}]'
    if isinstance(t, c_ast.PtrDecl):
        if isinstance(t.type, c_ast.FuncDecl): return '[:raw]'
        const, inner = pointee(t)
        if isinstance(inner, c_ast.PtrDecl):          # const char *const *
            _, inner2 = pointee(inner)
            if ident(inner2) == 'char': return '[:strs]'
            return '[:raw]'
        if ident(inner) == 'char': return '[:str]' if const else '[:raw]'
        if ident(inner) == 'void': return '[:raw]'
        ek = elem_kind(inner)
        if ek == '[:raw]': return '[:raw]'
        return f'[:vec {ek}]' if const else f'[:ref {ek}]'
    return elem_kind(t)

def count_field_for(tag, fname, names):
    """The sibling int field that counts the array field `fname`, if any."""
    cands = [fname + '_count']
    if fname.endswith('s'): cands.append(fname[:-1] + '_count')
    cands.append('count')
    for c in cands:
        if c in names: return c
    return None

def kebab(s): return s.replace('_', '-')

# --------------------------------------------------------------------------
# Enum keyword maps

def enum_keywords(vals):
    names = [n for n, _ in vals]
    toks = [n.split('_') for n in names]
    if len(toks) == 1:
        pre = len(toks[0]) - 1
    else:
        pre = 0
        while all(len(t) > pre + 1 for t in toks) and len({t[pre] for t in toks}) == 1:
            pre += 1
    return [('-'.join(t[pre:]).lower(), v) for t, (_, v) in zip(toks, vals)]

# --------------------------------------------------------------------------
# Icons and #define constants

def icon_defs():
    out = []
    for line in open(os.path.join(INC, 'cui', 'cui_icons.h')):
        m = re.match(r'#define (CUI_ICON_\w+)\s+"((?:\\x[0-9a-fA-F]{2})+)"', line)
        if m:
            raw = bytes(int(h, 16) for h in re.findall(r'\\x([0-9a-fA-F]{2})', m.group(2)))
            out.append((m.group(1), raw.decode('utf-8')))
    return out

def clj_str(s):
    o = []
    for ch in s:
        c = ord(ch)
        if ch == '"': o.append('\\"')
        elif ch == '\\': o.append('\\\\')
        elif 32 <= c < 127: o.append(ch)
        elif c <= 0xFFFF: o.append('\\u%04X' % c)
        else:
            c -= 0x10000
            o.append('\\u%04X\\u%04X' % (0xD800 + (c >> 10), 0xDC00 + (c & 0x3FF)))
    return '"' + ''.join(o) + '"'

# --------------------------------------------------------------------------
# Scope macros

def scope_macros():
    text = open(os.path.join(INC, 'cui', 'cui.h')).read()
    out = []
    for m in re.finditer(r'#define (cui_\w+)\(([^)]*)\)\s+CUI__SCOPE(_IF)?\((cui_\w+)\(([^;]*?)\),\s*(cui_\w+)\(\(?ui\)?\)\)\s*$',
                         text, re.M):
        name, params, cond, begin, bargs, end = m.groups()
        params = [p.strip() for p in params.split(',')]
        out.append(dict(name=name, params=params, cond=bool(cond), begin=begin, end=end,
                        has_opts='CUI__OPTS' in bargs))
    return out

# --------------------------------------------------------------------------
# Functions

FUNCS = {f[0]: f for f in M.funcs}
SKIP = {'cui_log_appendv'}          # va_list cannot be built from jolt

def param_kind(params, i):
    """(kind-string, absorbs-next) for parameter i."""
    pn, t = params[i]
    nxt = params[i + 1] if i + 1 < len(params) else None
    nname = nxt[0] if nxt else ''
    nint = nxt is not None and (scalar_of(nxt[1]) in (':int', ':size_t'))
    countish = nint and (nname == 'count' or nname.endswith('_count') or nname in ('cap', 'components', 'channels', 'days')
                         or nname.endswith('_cap'))
    s = tstr(t)
    if s in ('cui_ctx *', 'cui_app *'): return ('handle', False)
    if typedef_ptr(t): return ('raw', False)
    if isinstance(t, c_ast.PtrDecl):
        if isinstance(t.type, c_ast.FuncDecl): return ('raw', False)
        const, inner = pointee(t)
        if isinstance(inner, c_ast.PtrDecl):
            _, inner2 = pointee(inner)
            if ident(inner2) == 'char' and 'const' in getattr(inner2, 'quals', []) and countish:
                return ('strs', True)
            if ident(inner2) == 'char' and countish: return ('strs', True)
            return ('raw', False)
        if ident(inner) == 'char':
            if const:
                if nint and nname in ('len', 'length'): return ('str-len', True)
                return ('str', False)
            if nint or (nxt and scalar_of(nxt[1]) == ':size_t'): return ('buf', True)
            return ('raw', False)
        if ident(inner) == 'void': return ('raw', False)
        tag = struct_of(inner)
        if tag in ('cui_log_buffer', 'cui_log_line', 'cui_draw_data'): return ('raw', False)
        ek = elem_kind(inner)
        if ek == '[:raw]': return ('raw', False)
        if const:
            return (f'vec {ek}', True) if countish else (f'in {ek}', False)
        return (f'ref {ek}', countish and (tag is not None or nname in ('count', 'components')))
    if pn == 'mods' and scalar_of(t) == ':int': return ('mods', False)
    return ('val ' + elem_kind(t), False)

def raw_arg_type(t):
    s = tstr(t)
    if typedef_ptr(t): return ':pointer'
    if isinstance(t, c_ast.PtrDecl):
        const, inner = pointee(t)
        if ident(inner) == 'char' and const: return ':string'
        return ':pointer'
    tag = struct_of(t)
    if tag: return f'[:by-value {struct_desc(tag)}]'
    return ffi_type(t)

def raw_ret_type(t):
    if isinstance(t, c_ast.TypeDecl) and ident(t) == 'void': return ':void'
    return raw_arg_type(t)

def ret_kind(t):
    if isinstance(t, c_ast.TypeDecl) and ident(t) == 'void': return None
    tag = struct_of(t)
    if tag: return ('struct', TAG_TO_NAME[tag])
    if isinstance(t, c_ast.PtrDecl): return ('plain', None)
    e = enum_of(t)
    if e: return ('enum', e.replace('enum ', ''))
    return ('plain', None)

def clj_name(cname, taken):
    n = cname[4:] if cname.startswith('cui_') else cname
    if n.endswith('_ex') and ('cui_' + n[:-3]) not in FUNCS: n = n[:-3]
    return kebab(n)

# --------------------------------------------------------------------------
# Emit raw.clj

def emit_raw():
    o = []
    o.append(';; GENERATED by gen/gen.py from the CUI headers. Do not edit.\n')
    o.append('(ns cui.raw\n  "Every CUI C function, enum value and icon, bound 1:1 under its C name.\n'
             '  Option structs are pointers; build them with cui.impl/struct-in! or use cui.core."\n'
             '  (:require [jolt.ffi :as ffi]))\n')
    o.append(';; Outside a deps.edn project that declares :jolt/native, point CUI_LIB at libcui.')
    o.append('(when-let [lib (System/getenv "CUI_LIB")] (ffi/load-library lib))\n')
    o.append(';; ---- enum and flag constants\n')
    seen = set()
    for name, v in M.consts.items():
        if name in seen: continue
        seen.add(name)
        o.append(f'(def {name} {v})')
    o.append('\n;; ---- icons (UTF-8 strings drawn by the Lucide icon font)\n')
    for n, s in icon_defs():
        o.append(f'(def {n} {clj_str(s)})')
    o.append('\n(def CUI_VERSION_STRING "1.0.0")\n\n;; ---- functions\n')
    for name, ret, params, variadic in M.funcs:
        if name in SKIP: continue
        args = [raw_arg_type(t) for _, t in params]
        if variadic: args.append(':&')
        sig = ', '.join(f'{tstr(t)} {pn}' for pn, t in params) + (', ...' if variadic else '')
        o.append(f';; {tstr(ret)} {name}({sig})')
        o.append(f'(ffi/defcfn {name} "{name}" [{" ".join(args)}] {raw_ret_type(ret)})')
    return '\n'.join(o) + '\n'

# --------------------------------------------------------------------------
# Emit spec.clj

def emit_spec():
    o = [';; GENERATED by gen/gen.py from the CUI headers. Do not edit.\n',
         '(ns cui.spec\n  "Struct layouts, field kinds and enum keywords for every CUI type."\n'
         '  (:require [jolt.ffi :as ffi]))\n']
    o.append('(def layouts\n  {')
    for tag in sorted(M.structs):
        o.append(f'   {kw(TAG_TO_NAME[tag])} (ffi/layout {struct_desc(tag)})')
    o.append('  })\n')
    o.append(';; struct -> [[option-key c-field kind count-field] ...]\n(def fields\n  {')
    for tag in sorted(M.structs):
        names = [n for n, _ in field_list(tag)]
        rows = []
        for n, t in field_list(tag):
            k = field_kind(n, t)
            cf = count_field_for(tag, n, names) if k.startswith(('[:vec', '[:strs')) else None
            rows.append(f'[:{kebab(n)} :{n} {k} {(":" + cf) if cf else "nil"}]')
        o.append(f'   {kw(TAG_TO_NAME[tag])}\n   [' + '\n    '.join(rows) + ']')
    o.append('  })\n')
    o.append(';; enum -> {"keyword-name" value}\n(def enum-names\n  {')
    for e, vals in sorted(M.enums.items()):
        if e.startswith('enum '): continue
        pairs = ' '.join(f'"{k}" {v}' for k, v in enum_keywords(vals))
        o.append(f'   {kw(e)} {{{pairs}}}')
    o.append('  })\n')
    o.append('(def icon-names\n  {' + '\n   '.join(
        f'"{kebab(n[len("CUI_ICON_"):].lower())}" {clj_str(s)}' for n, s in icon_defs()) + '})\n')
    return '\n'.join(o)

# --------------------------------------------------------------------------
# Emit core.clj wrappers

def wrapper(name, ret, params, variadic, cname):
    """Source of one idiomatic wrapper fn."""
    plist, call, uses_wb, i = [], [], False, 0
    kinds = []
    while i < len(params):
        k, absorb = param_kind(params, i)
        pn = kebab(params[i][0] or f'a{i}')
        if pn in CORE_NAMES or pn == name: pn = pn + '*'
        kinds.append((pn, k, params[i]))
        if k == 'handle' or k == 'raw':
            plist.append(pn); call.append(pn)
        elif k == 'str':
            plist.append(pn); call.append(f'(impl/str-arg {pn})')
        elif k == 'str-len':
            plist.append(pn); call.append(f'(impl/str-arg {pn})'); call.append(f'(impl/utf8-len {pn})')
        elif k == 'strs':
            plist.append(pn); call.append(f'(impl/strs-ptr {pn})'); call.append(f'(count {pn})')
        elif k == 'buf':
            plist.append(pn); uses_wb = True
            call.append(f'(impl/buf-ptr! wb {pn})'); call.append(f'(impl/buf-cap {pn})')
        elif k == 'mods':
            plist.append(pn); call.append(f'(impl/mods {pn})')
        elif k.startswith('vec '):
            ek = k[4:]
            plist.append(pn); uses_wb = True
            call.append(f'(impl/vec-ptr! wb {ek} {pn})'); call.append(f'(count {pn})')
        elif k.startswith('in '):
            ek = k[3:]
            plist.append(pn); uses_wb = True
            call.append(f'(impl/in-ptr! wb {ek} {pn})')
        elif k.startswith('ref '):
            ek = k[4:]
            plist.append(pn); uses_wb = True
            call.append(f'(impl/ref-ptr! wb {ek} {pn})')
            if absorb: call.append(f'(impl/ref-count {pn})')
        elif k.startswith('val '):
            ek = k[4:]
            plist.append(pn)
            if ek.startswith('[:struct'):
                uses_wb = True; call.append(f'(impl/in-ptr! wb {ek} {pn})')
            else:
                call.append(f'(impl/arg-val {ek} {pn})')
        else:
            raise ValueError(k)
        i += 2 if absorb else 1

    rk = ret_kind(ret)
    rtag = rk[1] if rk and rk[0] == 'struct' else None
    head = ''
    if rtag:
        uses_wb = True
        call_expr = f'(raw/{cname} dst {" ".join(call)})'
        body = f'(let [dst (impl/alloc {kw(rtag)})] {call_expr} (impl/read-struct {kw(rtag)} dst))'
    else:
        body = f'(raw/{cname} {" ".join(call)})'
        if rk and rk[0] == 'enum': body = f'(impl/enum-key {kw(rk[1])} {body})'
    if variadic:
        # printf-style: format in Clojure, hand C a literal "%s"
        fmt_idx = plist.index(kebab(params[-1][0]))
        call2 = list(call); call2[-1] = '"%s"'
        body = f'(raw/{cname} {" ".join(call2)} (apply format {plist[fmt_idx]} args))'
        plist = plist + ['&', 'args']
    if uses_wb:
        body = f'(impl/with-call [wb] {body})'

    # optional trailing opts
    last = kinds[-1] if kinds else None
    opt_arity = (last is not None and last[1].startswith('in [:struct') and
                 TAG_ok_opts(last[2][1]) and not variadic)
    sig = ', '.join(f'{tstr(t)} {pn}' for pn, t in params) + (', ...' if variadic else '')
    doc = f'{tstr(ret)} {cname}({sig})'
    if opt_arity:
        short = plist[:-1]
        return (f'(defn {name}\n  {clj_str(doc)}\n'
                f'  ([{" ".join(short)}] ({name} {" ".join(short)} nil))\n'
                f'  ([{" ".join(plist)}]\n   {body}))\n')
    return f'(defn {name}\n  {clj_str(doc)}\n  [{" ".join(plist)}]\n  {body})\n'

def TAG_ok_opts(ptr_t):
    _, inner = pointee(ptr_t)
    tag = struct_of(inner)
    n = TAG_TO_NAME.get(tag, '')
    return n.endswith('_opts') or n in ('cui_box', 'cui_app_desc', 'cui_file_dialog_opts')

def emit_core(prelude):
    names, out, taken = {}, [], set()
    for name, ret, params, variadic in M.funcs:
        if name in SKIP or name == 'cui_app_run': continue
        n = clj_name(name, taken)
        assert n not in taken, n
        taken.add(n); names[name] = n
        out.append(wrapper(n, ret, params, variadic, name))
    macros = []
    for sm in scope_macros():
        n = kebab(sm['name'][4:])
        if n in taken: n = n + '-scope'
        taken.add(n)
        ps = [kebab(p) for p in sm['params'][1:]]
        ps = ['opts' if p == '...' else (p + '*' if p in CORE_NAMES else p) for p in ps]
        bfn, efn = names[sm['begin']], names[sm['end']]
        args = ' '.join('~' + p for p in ps)
        doc = (f"Scope: runs body between {sm['begin']} and {sm['end']}"
               + (' (only when begin returns true)' if sm['cond'] else '') + '.')
        if sm['cond']:
            body = (f'`(let [u# ~ui]\n     (when ({bfn} u# {args})\n'
                    f'       (try (do ~@body) (finally ({efn} u#))))))')
        else:
            body = (f'`(let [u# ~ui]\n     ({bfn} u# {args})\n'
                    f'     (try (do ~@body) (finally ({efn} u#)))))')
        macros.append(f'(defmacro {n}\n  {clj_str(doc)}\n  [ui {" ".join(ps)} & body]\n  {body}\n')
    excl = sorted(t for t in taken if t in CORE_NAMES)
    head = prelude.replace('%EXCLUDES%', ' '.join(excl))
    return (head + '\n;; ---- generated wrappers (one per C function)\n\n' + '\n'.join(out)
            + '\n;; ---- generated scope macros\n\n' + '\n'.join(macros)), taken

def main():
    src = os.path.join(ROOT, 'src', 'cui')
    os.makedirs(src, exist_ok=True)
    open(os.path.join(src, 'raw.clj'), 'w').write(emit_raw())
    open(os.path.join(src, 'spec.clj'), 'w').write(emit_spec())
    prelude = open(os.path.join(HERE, 'core_prelude.clj')).read()
    core, taken = emit_core(prelude)
    open(os.path.join(src, 'core.clj'), 'w').write(core)
    print(f'{len(M.funcs)} functions, {len(M.structs)} structs, {len(M.enums)} enums, '
          f'{len(icon_defs())} icons, {len(scope_macros())} scope macros, {len(taken)} public names')

if __name__ == '__main__':
    main()
