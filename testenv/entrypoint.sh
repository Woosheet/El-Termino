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

# A small HTTP service reachable only from inside the machine, for the Requests tab.
mkdir -p /srv/http/cgi-bin
printf '{"status":"ok","host":"%s"}' "$(hostname)" > /srv/http/health.json
cat > /srv/http/cgi-bin/echo <<'CGI'
#!/bin/sh
# Answers with the method, the query and the body it was sent.
printf 'Content-Type: text/plain\r\n\r\n'
printf '%s %s\n' "$REQUEST_METHOD" "$QUERY_STRING"
[ -n "${CONTENT_LENGTH:-}" ] && head -c "$CONTENT_LENGTH"
exit 0
CGI
chmod +x /srv/http/cgi-bin/echo
busybox httpd -p 127.0.0.1:8080 -h /srv/http

exec /usr/sbin/sshd -D -e -f /testenv/sshd_config
