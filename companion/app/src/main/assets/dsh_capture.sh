#!/system/bin/sh
# dsh_capture.sh —— companion 0.1.14 自愈保底：自起一个 dsh web 引擎，抓明文 launch token。
# 来源：项目内已被真机验证过的 scripts/capture-dsh-token.sh（token 只从引擎自己 stdout 出，
# 不经过 dsh-go App 的 LogRedactor 脱敏层）。companion 拿到 token 后走标准 GET /?token= 换 cookie。
# 副作用：会短暂 kill 现有引擎并重启一个（用户已知悉并同意此短暂中断）。最终只打印一行 TOKEN=xxx。
PKG="io.github.ccch1mneyyy.dsh_app"
ROOTFS="/data/user/0/$PKG/files/runtime/rootfs"
OUT="/data/local/tmp/watchctl-dsh.out"

PROOT_BIN=$(ls /data/app/*/*/lib/arm64/libproot.so 2>/dev/null | grep "$PKG" | head -1)
LIBTALLOC=$(ls /data/app/*/*/lib/arm64/libtalloc.so 2>/dev/null | grep "$PKG" | head -1)
[ -z "$PROOT_BIN" ] && PROOT_BIN=$(find /data/app -name libproot.so 2>/dev/null | grep "$PKG" | head -1)
[ -z "$LIBTALLOC" ] && LIBTALLOC=$(find /data/app -name libtalloc.so 2>/dev/null | grep "$PKG" | head -1)
[ -z "$PROOT_BIN" ] && { echo "ERR_NO_PROOT"; exit 1; }
LIBDIR=$(dirname "$LIBTALLOC"); APK_LIB=$(dirname "$PROOT_BIN")
[ -d "$ROOTFS" ] || { echo "ERR_NO_ROOTFS"; exit 1; }

# 清理旧引擎进程，释放 3080
NODE_PID=$(ps -A -o PID,CMD 2>/dev/null | grep '@deepseek-ai/dsh/lib/bin.js web' | grep -v grep | head -1 | awk '{print $1}')
PROOT_PID=$(ps -A -o PID,CMD 2>/dev/null | grep libproot | grep -v grep | head -1 | awk '{print $1}')
APP_PID=$(ps -A -o PID,CMD 2>/dev/null | grep "$PKG" | grep -v grep | head -1 | awk '{print $1}')
[ -n "$NODE_PID" ] && kill -9 $NODE_PID 2>/dev/null
[ -n "$PROOT_PID" ] && kill -9 $PROOT_PID 2>/dev/null
[ -n "$APP_PID" ] && kill -9 $APP_PID 2>/dev/null
sleep 1
INODE=$(grep -i " 0100007F:0C08 " /proc/net/tcp 2>/dev/null | head -1 | awk '{print $10}')
if [ -n "$INODE" ]; then
  for p in /proc/[0-9]*/fd/*; do
    tgt=$(readlink "$p" 2>/dev/null)
    echo "$tgt" | grep -q "socket:\[$INODE\]" && kill -9 "$(echo "$p" | cut -d/ -f3)" 2>/dev/null
  done
fi

: > "$OUT"
( setsid env LD_LIBRARY_PATH="$LIBDIR:$APK_LIB" "$PROOT_BIN" --kill-on-exit --link2symlink --sysvipc -L -0 \
  --rootfs="$ROOTFS" --cwd=/root \
  -b /dev -b /dev/urandom:/dev/random -b /proc -b /proc/self/fd:/dev/fd \
  -b /proc/self/fd/0:/dev/stdin -b /proc/self/fd/1:/dev/stdout -b /proc/self/fd/2:/dev/stderr \
  -b /sys -b "$ROOTFS/var/lib/dsh-app/empty:/sys/fs/selinux" -b "$ROOTFS/var/lib/dsh-app/shm:/dev/shm" \
  -b /system -b /apex \
  /usr/bin/env -i HOME=/root USER=root LOGNAME=root SHELL=/bin/bash \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=C.UTF-8 TERM=xterm-256color TMPDIR=/tmp DSH_HOME=/root/.dsh \
    XDG_DATA_DIRS=/usr/local/share:/usr/share:/system \
    pnpm_config_registry=https://registry.npmmirror.com/ \
    UV_USE_IO_URING=0 NARB_DISABLE_NATIVE_CACHE=1 \
    NODE_OPTIONS="--dns-result-order=ipv4first --max-old-space-size=2048 --import=/opt/dsh-go/direct/proot-dns.mjs" \
    TZ=Asia/Shanghai DSH_APP_WORKSPACE_RECOVERY=0 UV_THREADPOOL_SIZE=4 \
    DSH_GO_RUNTIME_ROOT="$ROOTFS" \
    /bin/sh -c 'exec /usr/local/bin/dsh web --patch /etc/dsh-app/dsh-web.patch.yml --patch /opt/dsh-app/speech.patch.yml --patch /opt/dsh-app/auth/auth.patch.yml --patch /opt/dsh-app/search.patch.yml --host 127.0.0.1 --port 3080 --no-open' \
  > "$OUT" 2>&1 < /dev/null & )

i=0
while [ $i -lt 25 ]; do
  TOKEN=$(grep -aoE 'token=[A-Za-z0-9._~-]{20,80}' "$OUT" | head -1 | cut -d= -f2)
  [ -n "$TOKEN" ] && { echo "TOKEN=$TOKEN"; exit 0; }
  i=$((i+1))
  sleep 1
done
echo "ERR_NO_TOKEN"
exit 2
