#!/usr/bin/env bash
# Minimal MySQL backup/verify/restore drill for the Compose topology.
# Usage:
#   bash tools/backup_restore.sh backup
#   bash tools/backup_restore.sh verify <file.sql.gz>
#   bash tools/backup_restore.sh restore-check <file.sql.gz>
#   bash tools/backup_restore.sh rollback-info
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-deploy/generated/docker-compose.yml}"
ENV_FILE="${ENV_FILE:-$HOME/nt-config/cluster.env}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/nt-evidence/backup}"
RESTORE_DB="${RESTORE_DB:-turn_based_game_nt_restore}"

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

backup() {
  mkdir -p "$BACKUP_DIR"
  chmod 700 "$BACKUP_DIR"
  local ts file
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  file="$BACKUP_DIR/nt-$ts.sql.gz"
  compose exec -T mysql sh -c \
    'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --set-gtid-purged=OFF "$MYSQL_DATABASE"' \
    | gzip -9 > "$file"
  sha256sum "$file" > "$file.sha256"
  echo "backup: $file"
  echo "sha256: $(cut -d' ' -f1 "$file.sha256")"
}

verify() {
  local file="${1:?usage: verify <file.sql.gz>}"
  sha256sum -c "$file.sha256"
  gzip -t "$file"
  echo "verify: OK"
}

restore_check() {
  local file="${1:?usage: restore-check <file.sql.gz>}"
  verify "$file"
  compose exec -T mysql sh -c \
    "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'DROP DATABASE IF EXISTS $RESTORE_DB; CREATE DATABASE $RESTORE_DB CHARACTER SET utf8mb4'"
  gunzip -c "$file" | compose exec -T mysql sh -c \
    "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $RESTORE_DB"
  local tables
  tables="$(compose exec -T mysql sh -c \
    "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -e \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$RESTORE_DB'\"" \
    | tr -d '\r')"
  echo "restore-check: OK, tables=$tables"
  compose exec -T mysql sh -c \
    "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'DROP DATABASE $RESTORE_DB'"
}

rollback_info() {
  echo "current image:"
  docker image inspect tbg-server:cluster --format '  id={{.Id}}' 2>/dev/null || true
  docker image inspect tbg-server:latest --format '  id={{.Id}}' 2>/dev/null || true
  echo "redeploy an old image: docker tag <old-image-id> tbg-server:cluster && compose up -d --no-build"
}

case "${1:-}" in
  backup) backup ;;
  verify) verify "${2:-}" ;;
  restore-check) restore_check "${2:-}" ;;
  rollback-info) rollback_info ;;
  *) echo "usage: $0 {backup|verify|restore-check|rollback-info} [file.sql.gz]"; exit 2 ;;
esac
