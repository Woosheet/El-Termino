#!/bin/sh
set -eu

ssh-keygen -A >/dev/null

K=/testenv/keys
if [ -f "$K/host_ed25519" ]; then
  install -m 600 "$K/host_ed25519" /etc/ssh/host_ed25519
  install -m 644 "$K/host_ed25519-cert.pub" /etc/ssh/host_ed25519-cert.pub
fi
if [ -f "$K/user_ca.pub" ]; then
  install -m 644 "$K/user_ca.pub" /etc/ssh/user_ca.pub
fi

install -d -m 700 -o dev -g dev /home/dev/.ssh
if [ -f "$K/authorized_keys" ]; then
  install -m 600 -o dev -g dev "$K/authorized_keys" /home/dev/.ssh/authorized_keys
fi
if [ -f /testenv/google_authenticator ]; then
  install -m 400 -o dev -g dev /testenv/google_authenticator /home/dev/.google_authenticator
fi
if [ -f /testenv/pam_sshd ]; then
  cp /testenv/pam_sshd /etc/pam.d/sshd
fi

exec /usr/sbin/sshd -D -e -f /testenv/sshd_config
