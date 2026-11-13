FROM ubuntu/squid@sha256:6a097f68bae708cedbabd6188d68c7e2e7a38cedd05a176e1cc0ba29e3bbe029
RUN apt-get update && apt-get -y upgrade && rm -rf /var/lib/apt/lists/*
# Turns the deployment's approved-hosts file into proxy rules before Squid starts.
COPY qa-entrypoint.sh /usr/local/bin/qa-entrypoint.sh
RUN chmod 0755 /usr/local/bin/qa-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/qa-entrypoint.sh"]
CMD ["-f", "/etc/squid/squid.conf", "-NYC"]
