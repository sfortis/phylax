# Security Policy

## Reporting a vulnerability

Please do not open a public issue. Use GitHub's private vulnerability reporting on this
repository, under the Security tab, "Report a vulnerability".

Report privately if you find a way to bypass authentication, reach a server the user has not
configured, read another app's data, or extract credentials, client certificates or session
tokens.

Include what you did, what you observed, and the app and Android versions. A short proof of
concept helps, but never post credentials, certificates or complete session tokens, in a
private report or in a public issue.

## Supported versions

Fixes go into the latest release. There are no maintained older branches.

## Scope

Phylax talks only to the Frigate server the user configures. It has no backend of its own, so
anything involving a server you do not control is out of scope here and belongs with that
server's maintainers.
