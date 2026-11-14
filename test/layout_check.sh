#!/bin/sh
# Compares every struct size and field offset jolt computes against the C
# compiler's sizeof/offsetof. Usage: test/layout_check.sh /path/to/CUI
set -e
CUI=$(cd "${1:-native/CUI}" && pwd)
cd "$(dirname "$0")/.."
gcc -E -P -undef -nostdinc -I gen/stubs -I "$CUI/include" -DCUI_API= gen/all.h -o gen/all.i
python3 gen/layout_c.py > /tmp/cui_layout.c
cc -I"$CUI/include" /tmp/cui_layout.c -o /tmp/cui_layout
/tmp/cui_layout | sort > /tmp/cui_layout_c.txt
jolt -A:test -e "(require 'cui.impl) (doseq [[t {:keys [size fields]}] cui.impl/structs] (println (name t) \"size\" size) (doseq [f fields] (println (name t) (name (:ckey f)) (:off f))))" | sort > /tmp/cui_layout_jolt.txt
diff /tmp/cui_layout_c.txt /tmp/cui_layout_jolt.txt
echo "layouts match: $(wc -l < /tmp/cui_layout_c.txt) sizes and offsets"
