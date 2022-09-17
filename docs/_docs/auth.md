---
title: Authentication
category: root
order: 1
permalink: /auth
layout: default
---

its built for a keycloak server.

set some enviornment variables:

- `OAUTH_AUDIENCE`
- `OAUTH_CLIENT_ID`
- `OAUTH_ISSUER`
- `OAUTH_JWKS_URL`

### HTTP

Send a bearer token over in the headers of your HTTP requests.  
`Authorization: Bearer <token>`

### Sockets

For socket connection requests, there are limitations in the headers that are allowed to be sent. For this reason,
authenticate with the socket by sending an "Authorization" method call as the first message on connect.

```json
{
  "jsonrpc": "2.0",
  "method": "authorizaton",
  "params": {
    "token": "<token>"
  }
}
```

for long-lived sockets, send this call again with a new token when your token is refreshed. Sending messages via the
socket that require authentication will fail if the last token sent to the server is expired.
