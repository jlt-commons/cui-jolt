"""Prints a C program that reports sizeof/offsetof for every CUI struct."""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from pycparser import c_ast
from model import Model, parse
m = Model(parse(os.path.join(os.path.dirname(__file__), 'all.i')))
def tdname(tag):
    for n, t in m.typedefs.items():
        if isinstance(t, c_ast.TypeDecl) and isinstance(t.type, c_ast.Struct) and t.type.name == tag:
            return n
    return 'struct ' + tag
out = ['#include <stdio.h>', '#include <stddef.h>', '#include <cui/cui.h>', '#include <cui/cui_app.h>', 'int main(void){']
for tag in sorted(m.structs):
    T = tdname(tag); key = T.replace('struct ', '')
    out.append(f'printf("{key} size %zu\\n", sizeof({T}));')
    for d in m.structs[tag].decls:
        out.append(f'printf("{key} {d.name} %zu\\n", offsetof({T}, {d.name}));')
out.append('return 0;}')
print('\n'.join(out))
