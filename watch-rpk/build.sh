#!/usr/bin/env bash
# 打包表端 Vela 快应用 rpk（官方 aiot-toolkit，非手机快应用的 hap-toolkit）
# 用法： bash build.sh
# 每次执行自动 versionCode +1 / versionName 末段 +1，保证手表识别为新版本可覆盖安装。
set -e
cd "$(dirname "$0")"

echo ">> 自动升版本号（versionCode +1, versionName 末段 +1）"
python3 - <<'PY'
import json
p = 'src/manifest.json'
m = json.load(open(p, encoding='utf-8'))
m['versionCode'] = int(m.get('versionCode', 1)) + 1
parts = m.get('versionName', '0.1.0').split('.')
parts[-1] = str(int(parts[-1]) + 1)
m['versionName'] = '.'.join(parts)
json.dump(m, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print(f"   -> versionName={m['versionName']} versionCode={m['versionCode']}")
PY

echo ">> 清理旧工具链(hap-toolkit)遗留产物"
rm -rf build dist

echo ">> npm install (aiot-toolkit)"
npm install >/dev/null 2>&1 || true

echo ">> aiot release --enable-jsc (官方 Vela 打包器，PRODUCTION + 字节码)"
echo "   必须用 release+jsc，不能用 aiot build："
echo "   真机 AIOTJS 引擎只加载 app.jsc 字节码；aiot build 产出的是明文 .js debug 包，"
echo "   引擎报 Failed to open app.jsc -> 框架初始化失败 -> Abort App -> arm_memfault PANIC -> 整机重启。"
echo "   AstroBox 安装流程末尾会自动拉起应用，这一拉起就把整表带崩，表现为『安装过程中重启/安装失败』。"
npx aiot release --enable-jsc

echo ">> 校验产物：必须只含 .jsc，绝不能残留 .js"
python3 - <<'PY'
import glob, zipfile, sys
rpks = glob.glob('dist/*.rpk')
assert len(rpks) == 1, f'期望 1 个 rpk，实际 {rpks}'
z = zipfile.ZipFile(rpks[0])
js  = [n for n in z.namelist() if n.endswith('.js')]
jsc = [n for n in z.namelist() if n.endswith('.jsc')]
print('   jsc:', jsc)
print('   残留 .js:', js)
assert not js, '产物里仍有明文 .js，真机会崩，禁止交付'
assert 'app.jsc' in jsc, '缺少 app.jsc 入口'
print('   [OK] 字节码入口齐全')
PY

# 自动整理唯一交付目录：文件名精简 + 各自真实版本号（不跑这步交付目录会残留旧名）
bash ../deliver.sh

echo ""
echo "签名：release 使用 sign/release/{private.pem,certificate.pem}，"
echo "  与 sign/debug 及 companion（单 APK）是同一张证书（SHA-256 8637ca4e…d12c），"
echo "  因此切换 release 打包不会破坏 interconnect 的签名一致性校验。"
echo "  手机端早已不再有独立的 LSPosed 模块 APK（0.1.8 起指令引擎内置）；"
echo "  若改动任一端签名，两端必须一起重新打包，否则 interconnect 报签名不一致。"
echo ""
echo "真机安装：先卸载旧包再装（Vela 的 force-stop 不清状态）。"
