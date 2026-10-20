#!/usr/bin/env bash
# ============================================================
# 自签名开发证书生成（SSL 卸载联调用）
# ⚠️ 仅限开发与测试环境；生产环境必须使用正式 CA 签发证书（如 Let's Encrypt）
# 用法：bash tools/gen-certs.sh   （输出到 nginx/certs/）
# ============================================================
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)/nginx/certs"
mkdir -p "$DIR"
# 兼容 Git Bash：cd 进目标目录用相对输出路径；//CN= 双斜杠避免 MSYS2 把 -subj 当路径转换
# （Linux openssl 对 //CN= 同样兼容）；原生 Windows 环境请自行调整路径写法
cd "$DIR"
rm -f tls.key tls.crt
MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 \
    -keyout tls.key -out tls.crt \
    -days 365 -nodes \
    -subj "//CN=tbg.nt.local" \
    -addext "subjectAltName=DNS:localhost,DNS:tbg.nt.local,IP:127.0.0.1,IP:10.244.216.57,IP:10.244.170.211"
echo "已生成自签证书: $DIR/tls.crt / tls.key（365 天，仅开发测试用）"
