#!/bin/sh
#
# metaloom-graph-cli inside the JVM image.
#
# A launcher rather than a second CMD, so that the tool is a command with a name:
#
#   docker exec g metaloom-graph-cli database backup default --server=http://localhost:8080 --out=- > g.dump
#
# --enable-native-access even though the jar manifest already carries Enable-Native-Access, for the
# same reason the server's CMD repeats it: a future JDK turns that warning into an error, and the
# manifest entry is one shade-config edit away from being lost.
#
# The native image has no JVM and no jar, and ships the compiled binary at this same path instead.
# Whichever image is running, the command is spelled the same way.
#
exec java --enable-native-access=ALL-UNNAMED -jar /graph/graph-cli.jar "$@"
