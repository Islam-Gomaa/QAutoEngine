#!/usr/bin/env bash
# Private CA for internal TLS. Existing files are kept. The CA key is never mounted into a container.
set -euo pipefail
cd "$(dirname "$0")/../deploy/secrets"
mkdir -p pki/ca pki/platform pki/worker pki/edge
days=397
[ -f pki/ca/ca.key ] || (umask 077; openssl ecparam -name prime256v1 -genkey -noout -out pki/ca/ca.key)
[ -f pki/ca/ca.crt ] || openssl req -x509 -new -key pki/ca/ca.key -sha256 -days 1825 -subj "/CN=QA Engine Internal CA" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out pki/ca/ca.crt
issue() { # dir cn san eku
  [ -f "$1/$2.crt" ] && return 0
  (umask 077; openssl ecparam -name prime256v1 -genkey -noout -out "$1/$2.key")
  openssl req -new -key "$1/$2.key" -subj "/CN=$2" -out "$1/$2.csr"
  printf 'subjectAltName=%s\nextendedKeyUsage=%s\nkeyUsage=critical,digitalSignature\nbasicConstraints=critical,CA:FALSE\n' "$3" "$4" > "$1/ext"
  openssl x509 -req -in "$1/$2.csr" -CA pki/ca/ca.crt -CAkey pki/ca/ca.key -CAcreateserial -days "$days" -sha256 -extfile "$1/ext" -out "$1/$2.crt" 2> /dev/null
  rm -f "$1/$2.csr" "$1/ext"
}
issue pki/platform platform "DNS:platform,DNS:gateway,DNS:localhost" serverAuth
issue pki/worker worker "DNS:worker" clientAuth
[ -f pki/password ] || (umask 077; openssl rand -hex 24 > pki/password)
pw=$(cat pki/password)
[ -f pki/worker/worker.p12 ] || openssl pkcs12 -export -inkey pki/worker/worker.key -in pki/worker/worker.crt -name worker -passout "pass:$pw" -out pki/worker/worker.p12
mkp12trust() { [ -f "$1" ] || keytool -importcert -noprompt -alias qa-ca -file pki/ca/ca.crt -keystore "$1" -storetype PKCS12 -storepass "$pw" > /dev/null 2>&1; }
mkp12trust pki/worker/truststore.p12
mkp12trust pki/platform/truststore.p12
cp pki/password pki/worker/password; cp pki/password pki/platform/password
cp pki/ca/ca.crt pki/edge/ca.crt
chmod 644 pki/platform/* pki/worker/* pki/edge/*
chmod 700 pki/ca
echo "PKI ready"
