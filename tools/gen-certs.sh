#!/usr/bin/env bash
# ============================================================
# 自签名开发证书生成（SSL 卸载联调用）
# ⚠️ 仅限开发与测试环境；生产环境必须使用正式 CA 签发证书（如 Let's Encrypt）
#
# 用法：bash tools/gen-certs.sh
#   CERT_SAN  覆盖 SAN（逗号分隔，默认 DNS:localhost,DNS:tbg.nt.local,IP:127.0.0.1）
#   CERT_DAYS 有效天数（默认 365）
# 输出到 nginx/certs/（已加入 .gitignore；历史跟踪的私钥需由仓库所有者 git rm --cached 轮换）
# ============================================================
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)/nginx/certs"
mkdir -p "$DIR"
SAN="${CERT_SAN:-DNS:localhost,DNS:tbg.nt.local,IP:127.0.0.1}"
DAYS="${CERT_DAYS:-365}"
# Git Bash/MSYS 需要双斜杠避免路径转换；Linux 用单斜杠
SUBJ="/CN=tbg.nt.local"
case "$OSTYPE" in
    msys*|cygwin*) SUBJ="//CN=tbg.nt.local" ;;
esac
cd "$DIR"
rm -f tls.key tls.crt
MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 \
    -keyout tls.key -out tls.crt \
    -days "$DAYS" -nodes \
    -subj "$SUBJ" \
    -addext "subjectAltName=$SAN"
chmod 600 tls.key
echo "已生成自签证书: $DIR/tls.crt / tls.key（${DAYS} 天，SAN=$SAN，仅开发测试用）"
