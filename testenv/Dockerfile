FROM debian:trixie-slim

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openssh-server libpam-google-authenticator \
      vim htop tmux mc less procps locales busybox curl openssl \
 && rm -rf /var/lib/apt/lists/* \
 && sed -i 's/^# *\(en_US.UTF-8\)/\1/' /etc/locale.gen && locale-gen \
 && mkdir -p /run/sshd \
 && useradd -m -s /bin/bash dev \
 && echo 'dev:devpass' | chpasswd

ENV LANG=en_US.UTF-8

COPY entrypoint.sh /entrypoint.sh
COPY fake-docker /usr/local/bin/docker
RUN chmod +x /entrypoint.sh /usr/local/bin/docker

EXPOSE 22
ENTRYPOINT ["/entrypoint.sh"]
