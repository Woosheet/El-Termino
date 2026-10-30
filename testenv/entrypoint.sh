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
# Its own OpenAPI description, where FastAPI keeps it, for the import from the host.
cat > /srv/http/openapi.json <<'JSON'
{"openapi":"3.0.3","info":{"title":"Servizio di prova","version":"1"},
 "paths":{
  "/health.json":{"get":{"tags":["Stato"],"summary":"Salute","responses":{"200":{"description":"ok"}}}},
  "/cgi-bin/echo":{"post":{"tags":["Eco"],"summary":"Eco",
    "parameters":[{"name":"x","in":"query","required":true,"schema":{"type":"integer","example":1}}],
    "requestBody":{"content":{"application/x-www-form-urlencoded":{"schema":{"type":"object","properties":{"nome":{"type":"string","example":"Anna"}}}}}},
    "responses":{"200":{"description":"eco"}}}}
 }}
JSON
# Sends to the address after "to=", or to itself for ever without one.
cat > /srv/http/cgi-bin/redirect <<'CGI'
#!/bin/sh
target=${QUERY_STRING#to=}
[ -n "$target" ] || target=/cgi-bin/redirect
printf 'Status: 302 Found\r\nLocation: %s\r\n\r\n' "$target"
exit 0
CGI
chmod +x /srv/http/cgi-bin/redirect
# HTTPS with a self-signed certificate, for the Requests tab.
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 -subj "/CN=$(hostname)" \
  -keyout /srv/tls.key -out /srv/tls.crt 2>/dev/null
openssl s_server -quiet -accept 127.0.0.1:8443 -key /srv/tls.key -cert /srv/tls.crt -www >/dev/null 2>&1 &

busybox httpd -p 127.0.0.1:8080 -h /srv/http

exec /usr/sbin/sshd -D -e -f /testenv/sshd_config
