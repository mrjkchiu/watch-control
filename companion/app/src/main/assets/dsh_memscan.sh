#!/system/bin/sh
# dsh-go launch-token 内存扫描（Android toybox 纯 shell，零外部依赖）。
# 由 DshSelfHeal 通过 su 以 root 执行。只读 /proc，不改任何东西。
# 输出到 stdout：候选 token（每行一个 43 位 base64url 串），Java 层逐个 GET /?token= 验证。
#
# 关键（0.1.18）：端口反查必须批量 ls -l 一次读取全部 fd 符号链接，
# 绝不能对每个 fd 单独 readlink（真机几千次 fork → 慢到超时，这是 0.1.15/0.1.16 失败根因）。
# 抓候选必须 tr -c 转行 + grep 整行精确 43 位，不能用 grep -oE '{43}'（会把真 token 打碎）。
# 以上两点均已用 busybox(toybox 同系) + 真机诊断输出双重验证。

PORT_HEX=$(printf '%04X' 3080)   # 3080 = 0C08

# 1) 找监听 3080 的 LISTEN socket inode（tcp + tcp6，状态 0A）
INODES=$(awk -v p=":$PORT_HEX" 'tolower($2) ~ tolower(p) "$" && $4=="0A" {print $10}' \
        /proc/net/tcp /proc/net/tcp6 2>/dev/null)
[ -z "$INODES" ] && exit 1

# 2) 批量反查持有该 socket 的 PID（一次 ls -l，零 fork 爆炸）
FDLIST=$(ls -l /proc/[0-9]*/fd/* 2>/dev/null)
PID=""
for i in $INODES; do
    line=$(echo "$FDLIST" | grep -F "socket:[$i]" | head -1)
    if [ -n "$line" ]; then
        PID=$(echo "$line" | sed -n 's|.*/proc/\([0-9][0-9]*\)/fd/.*|\1|p')
        [ -n "$PID" ] && break
    fi
done
[ -z "$PID" ] && exit 2

MAPS=/proc/$PID/maps
MEM=/proc/$PID/mem
[ -r "$MAPS" ] || exit 3

# 3) 筛匿名可写段（匿名 / [heap] / [anon:*]，排除文件映射），>=256KB 才扫。
#    结果直接进管道到 stdout，不落临时文件。
awk '{
    n = split($1, a, "-")
    perms = $2
    name = (NF >= 6) ? $NF : ""
    if (perms ~ /^rw/ && (NF < 6 || name == "" || name ~ /^\[/))
        printf "%s %s\n", a[1], a[2]
}' "$MAPS" | while read s e; do
    start=$((0x$s))
    end=$((0x$e))
    cb=$(((end - start) / 4096))
    [ "$cb" -lt 64 ] && continue
    sb=$((start / 4096))
    # tr 把非 token 字符转成换行 → 一段段连续 token 串 → grep 取长度恰为 43 的行
    dd if="$MEM" bs=4096 skip="$sb" count="$cb" 2>/dev/null \
        | tr -c 'A-Za-z0-9_-' '\n' \
        | grep -E '^[A-Za-z0-9_-]{43}$'
done | sort -u | head -n 400
