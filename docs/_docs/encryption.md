---
title: Encryption
category: root
order: 2
permalink: /encryption
layout: default
---

All chat-related communication between the server and client must be encrypted using Elliptic-curve Diffie–Hellman key
exchange.

The generated key pair must use the NIST P-256 curve. Encryption must use the AES algorithm in CBC mode and a randomly
generated, 16-byte initialization vector. Don't try to tell me these are bad choices, I don't care.

> NOTE: Server keys can change at any time, so any client that stores the server's public key locally must check for
> updates during a key exchange.

### HTTP

On every encrypted request, send the client's public key in the custom `BEC-Client-Key` header. If there is a response,
the server will include its public key in the custom `BEC-Server-Key` header.

### Sockets

### What to send

Any rpc call that requires encryption should encrypt all parameters together. The parameters of the call will then be an
object with keys `iv` and `content`.

_Unencrypted params:_
```json
{
    "param1": 123,
    "param2": "abc"
}
```

_Encrypted params:_
```json
{
    "iv": "u8ozelLjC6xHD+fZuOXztQ==",
    "content": "osH6dBOvE/zi...56S7KzsE2o6XuCa3hKLI="
}
```

_RPC call body:_
```json
{
    "jsonrpc": "2.0",
    "method": "messages.send",
    "params": {
        "iv": "u8ozelLjC6xHD+fZuOXztQ==",
        "content": "osH6dBOvE/zi...56S7KzsE2o6XuCa3hKLI="
    },
    "id": 4
}
```
