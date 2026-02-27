#!/bin/sh
set -eu

if [ -z "${AZURE_STORAGE_CONNECTION_STRING}" ]; then
    echo "Error - AZURE_STORAGE_CONNECTION_STRING must be set"
    exit 1
fi
CS="${AZURE_STORAGE_CONNECTION_STRING}"

echo "Waiting for Azurite..."
i=0
until az storage container list --connection-string "$CS" >/dev/null 2>&1; do
  i=$((i+1))
  [ "$i" -lt 60 ] || { echo "Azurite not ready"; exit 1; }
  sleep 1
done

echo "Creating documents container..."
az storage container create \
  --name documents \
  --public-access blob \
  --connection-string "$CS" >/dev/null

echo "Enforcing public access..."
az storage container set-permission \
  --name documents \
  --public-access blob \
  --connection-string "$CS" >/dev/null

echo "Uploading source.pdf..."
[ -f /seed/source.pdf ] || { echo "Missing /seed/source.pdf"; ls -la /seed; exit 1; }

az storage blob upload \
  --overwrite true \
  --container-name documents \
  --name source.pdf \
  --file /seed/source.pdf \
  --content-type application/pdf \
  --connection-string "$CS" >/dev/null

echo "Verifying source blob exists..."
az storage blob show \
  --container-name documents \
  --name source.pdf \
  --connection-string "$CS" >/dev/null

ACL="$(az storage container show --name documents --connection-string "$CS" --query properties.publicAccess -o tsv || true)"
echo "documents publicAccess=$ACL"

echo "Seeded URL: http://azurite:10000/devstoreaccount1/documents/source.pdf"
