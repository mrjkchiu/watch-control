#!/usr/bin/env bash
# 交付整理：把两端最终产物按「精简名 + 各自真实版本号」收集到 /workspace/交付/
# 0.1.8 单 APK 合并：手机端只剩 companion 一个 APK（指令引擎已内置），
#   独立的 LSPosed module APK 不再交付；表端 rpk 协议未变、代码未改。
# 规则（以后固定）：
#   rpk        -> watch-<versionName>.rpk        （版本读 src/manifest.json）
#   companion  -> companion-<versionName>.apk    （版本读 app/build.gradle）
# 版本号一律从各自配置实时读取，绝不硬编码，避免标错。交付目录每次先清空再生成，
# 保证永远只有这 2 个文件、名字精简且带版本号。
# 用法： bash deliver.sh   （build.sh 打包 rpk 后已自动调用；单独改了 APK 也可手动跑）
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"

python3 - "$ROOT" "/workspace/交付" <<'PY'
import sys, os, re, json, shutil, glob
root, out = sys.argv[1], sys.argv[2]

def gradle_ver(path):
    s = open(path, encoding='utf-8').read()
    m = re.search(r"versionName\s+['\"]([^'\"]+)['\"]", s)
    return m.group(1) if m else '0.0.0'

def rpk_ver(path):
    return json.load(open(path, encoding='utf-8'))['versionName']

def find_one(pattern):
    g = glob.glob(os.path.join(root, pattern), recursive=True)
    return g[0] if g else None

v_rpk  = rpk_ver(os.path.join(root, 'watch-rpk/src/manifest.json'))
v_comp = gradle_ver(os.path.join(root, 'companion/app/build.gradle'))

rpk  = find_one('watch-rpk/dist/*.rpk')
# 只取 release 产物：递归 /**/*.apk 会连 debug/ 里的旧包一起匹配到，
# 曾导致交付把 01:19 的旧 0.1.14 debug 包改名成新版号（改动没进包）。
comp = find_one('companion/app/build/outputs/apk/release/*.apk')
if comp is None:
    raise SystemExit('找不到 companion release APK，请先执行 ./gradlew assembleRelease')

# 清空交付目录（只删文件，保留目录），杜绝旧名残留（含旧 module-*.apk）
os.makedirs(out, exist_ok=True)
for f in os.listdir(out):
    p = os.path.join(out, f)
    if os.path.isfile(p):
        os.remove(p)

plan = [
    (rpk,  'watch-%s.rpk'      % v_rpk),
    (comp, 'companion-%s.apk'  % v_comp),
]
print('>> 交付目录整理（单 APK 架构 · 恒 2 文件 · 精简名 + 各自真实版本号）：')
for src, name in plan:
    if src:
        shutil.copy2(src, os.path.join(out, name))
        print('   %-28s <- %s' % (name, os.path.relpath(src, root)))
    else:
        print('   [跳过] 未找到产物：%s' % name)
PY

echo ""
echo "[OK] 交付目录："
ls -1 /workspace/交付/
