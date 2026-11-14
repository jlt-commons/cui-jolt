"""Turns the preprocessed CUI headers into a plain data model."""
from pycparser import c_parser, c_ast
import re

def parse(path='all.i'):
    return c_parser.CParser().parse(open(path).read(), path)

class Model:
    def __init__(self, ast):
        self.typedefs = {}   # name -> type node
        self.structs = {}    # struct tag -> c_ast.Struct with decls
        self.enums = {}      # enum typedef name -> [(name, value)]
        self.consts = {}     # enumerator name -> value
        self.enum_names = set()
        self.funcs = []      # (name, ret, [(pname, ptype)], variadic)
        for ext in ast.ext:
            self._collect_types(ext)
        for ext in ast.ext:
            if isinstance(ext, c_ast.Decl) and isinstance(ext.type, c_ast.FuncDecl):
                self._func(ext)

    def _eval(self, e):
        if e is None: return None
        if isinstance(e, c_ast.Constant):
            v = e.value.rstrip('uUlL')
            return int(v, 0)
        if isinstance(e, c_ast.ID): return self.consts[e.name]
        if isinstance(e, c_ast.UnaryOp):
            v = self._eval(e.expr)
            return {'-': -v, '+': v, '~': ~v}[e.op]
        if isinstance(e, c_ast.BinaryOp):
            a, b = self._eval(e.left), self._eval(e.right)
            return {'+': a+b, '-': a-b, '*': a*b, '<<': a<<b, '>>': a>>b, '|': a|b, '&': a&b}[e.op]
        if isinstance(e, c_ast.Cast): return self._eval(e.expr)
        raise ValueError(e)

    def _enum(self, enum, tname):
        vals, nxt = [], 0
        for en in (enum.values.enumerators if enum.values else []):
            v = self._eval(en.value) if en.value is not None else nxt
            self.consts[en.name] = v
            vals.append((en.name, v)); nxt = v + 1
        if tname:
            self.enums[tname] = vals
            self.enum_names.add(tname)
        if enum.name:
            self.enums['enum ' + enum.name] = vals
        return vals

    def _walk_for_types(self, node):
        if isinstance(node, c_ast.Struct) and node.decls is not None:
            self.structs[node.name] = node
            for d in node.decls: self._walk_for_types(d.type)
        elif isinstance(node, c_ast.Union) and node.decls is not None:
            for d in node.decls: self._walk_for_types(d.type)
        elif isinstance(node, (c_ast.TypeDecl, c_ast.PtrDecl, c_ast.ArrayDecl)):
            self._walk_for_types(node.type)

    def _collect_types(self, ext):
        if isinstance(ext, c_ast.Typedef):
            self.typedefs[ext.name] = ext.type
            t = ext.type
            if isinstance(t, c_ast.TypeDecl) and isinstance(t.type, c_ast.Enum):
                self._enum(t.type, ext.name)
            self._walk_for_types(t)
        elif isinstance(ext, c_ast.Decl):
            if isinstance(ext.type, c_ast.Enum):
                self._enum(ext.type, None)
            elif isinstance(ext.type, c_ast.Struct):
                self._walk_for_types(ext.type)

    def _func(self, d):
        fd = d.type
        params, variadic = [], False
        for p in (fd.args.params if fd.args else []):
            if isinstance(p, c_ast.EllipsisParam): variadic = True; continue
            if isinstance(p.type, c_ast.TypeDecl) and getattr(p.type.type, 'names', None) == ['void']: continue
            params.append((p.name, p.type))
        self.funcs.append((d.name, fd.type, params, variadic))

def tstr(t):
    """A readable C spelling of a type node."""
    if isinstance(t, c_ast.TypeDecl):
        q = 'const ' if 'const' in t.quals else ''
        inner = t.type
        if isinstance(inner, c_ast.IdentifierType): return q + ' '.join(inner.names)
        if isinstance(inner, c_ast.Struct): return q + 'struct ' + str(inner.name)
        if isinstance(inner, c_ast.Union): return q + 'union ' + str(inner.name)
        if isinstance(inner, c_ast.Enum): return q + 'enum ' + str(inner.name)
    if isinstance(t, c_ast.PtrDecl):
        if isinstance(t.type, c_ast.FuncDecl): return 'fnptr'
        q = ' const' if 'const' in t.quals else ''
        return tstr(t.type) + ' *' + q
    if isinstance(t, c_ast.ArrayDecl): return tstr(t.type) + '[]'
    if isinstance(t, c_ast.FuncDecl): return 'fn'
    return repr(type(t))
