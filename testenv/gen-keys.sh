#!/bin/sh
# Creates throwaway keys for the local test servers in testenv/keys/.
# They only ever authenticate against these containers: never reuse them.
set -eu
cd "$(dirname "$0")"
mkdir -p keys
cd keys

if [ -f id_ed25519 ] && [ -d expired ]; then
  echo "testenv/keys already populated, delete it to regenerate"
  exit 0
fi

if [ ! -f id_ed25519 ]; then
ssh-keygen -q -t ed25519 -N '' -C test-ed25519 -f id_ed25519
ssh-keygen -q -t rsa -b 3072 -N '' -C test-rsa -f id_rsa
ssh-keygen -q -t ecdsa -b 256 -N '' -C test-ecdsa -f id_ecdsa
ssh-keygen -q -t ed25519 -N 'testpass' -C test-ed25519-enc -f id_ed25519_enc
cat id_ed25519.pub id_rsa.pub id_ecdsa.pub id_ed25519_enc.pub > authorized_keys

# User certificate: id_cert is deliberately missing from authorized_keys,
# so a login with it proves the certificate was used.
ssh-keygen -q -t ed25519 -N '' -C test-user-ca -f user_ca
ssh-keygen -q -t ed25519 -N '' -C test-cert -f id_cert
ssh-keygen -q -s user_ca -I test-cert -n dev -V +520w id_cert.pub

# Host certificate for the "basic" server.
ssh-keygen -q -t ed25519 -N '' -C test-host-ca -f host_ca
ssh-keygen -q -t ed25519 -N '' -C basic-host -f host_ed25519
ssh-keygen -q -s host_ca -h -I basic-host -n localhost,127.0.0.1,10.0.2.2,basic -V +520w host_ed25519.pub
echo "@cert-authority [localhost]:2201,[127.0.0.1]:2201 $(cat host_ca.pub)" > known_hosts_ca

chmod 600 id_* user_ca host_ca host_ed25519
fi

# Same CA, but a host certificate that ran out last week, for the "expired" server.
mkdir -p expired
ssh-keygen -q -t ed25519 -N '' -C expired-host -f expired/host_ed25519
ssh-keygen -q -s host_ca -h -I expired-host -n localhost,127.0.0.1 -V -2w:-1w expired/host_ed25519.pub
cp authorized_keys user_ca.pub expired/
chmod 600 expired/host_ed25519
echo "Keys written to $(pwd)"
