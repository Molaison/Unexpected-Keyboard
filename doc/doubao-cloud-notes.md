# Doubao cloud channel — architecture notes

How the cloud pinyin / association channel is put together, and what an
integrator has to provide. This is design documentation; nothing here is
executed by the keyboard.

## Shape

The cloud channel is a long-lived connection, independent of the speech
recogniser. Candidates are an enhancement: any failure falls back to the local
engine and must never block input.

| item | value |
|---|---|
| gateway | `ime-gw3.oceancloudapi.com` |
| transport | QUIC (UDP 443) |
| ALPN | `h3` |
| security | mutual TLS, client identity `CN=ImeNetSDK` |
| envelope | JSON, one request per bidirectional stream |

QUIC is chosen for latency (small, frequent payloads, 0-RTT) and mTLS for
credential granularity: authentication lives in the transport, so the business
layer carries no token.

## Requirements on the client

The gateway only speaks QUIC:

| attempt | result |
|---|---|
| TCP 443 | connection dropped |
| UDP 443 without a client certificate | handshake fails |
| UDP 443 with the client certificate | handshake succeeds |

So an integrator needs a QUIC + TLS 1.3 stack, a client certificate and key
(PKCS-8), and ALPN set to `h3`. HTTP clients such as OkHttp do not speak HTTP/3.

The certificate is issued by the service provider (`CN=ImeNetSDK`, issuer
`CN=Bytedance`, valid to 2030-08). It identifies the application, not the user;
per-user identity travels in the request body as the device id.

## Framing

One request occupies one bidirectional stream. The request is a JSON envelope
whose `data` field carries the inner payload as **raw (unpadded) base64**; the
response is plain JSON on the same stream.

```json
{ "aid": "<route>",
  "version": "<monotonic ms>",
  "data": "<raw base64>",
  "did": "<device id>",
  "app_version": "1.5.1",
  "app_id": "401734",
  "os_type": "Android" }
```

Keys are lowercase — the edge rejects `Data` with `502`. `version` is a
monotonic clock reading, used only for freshness.

## Routes

`aid` also selects the route. Unknown values answer
`{"code":400,"msg":"App.handle[<aid>] not found"}`, which makes routing mistakes
obvious.

| route | purpose |
|---|---|
| `ime-cloud` | pinyin candidates |
| `ime-cloud-word_association` | word association |
| `ime-cloud-prefetch_ctx_regulator` | context prefetch |

Corresponding URL forms, used for logging and configuration:

```
/obric/ime/cloud/convert
/obric/ime/cloud/asso
/obric/ime/cloud/wordasso
/obric/ime/cloud/prefetch_ctx_regulator
/obric/ime/cloud/gethotword?version=<v>
/obric/ime/cloud/getdelword?version=<v>
```

## Association payload

| field | meaning |
|---|---|
| `Round` | round index (capitalised) |
| `pinyin` / `input` | pinyin string and raw input |
| `extend` / `msg` | passthrough |
| `keyboard_type` / `input_type` | keyboard and input mode |
| `rank_version` | ranking model version |
| `rounds_history` | previous rounds |
| `text_before_cursor` / `text_after_cursor` | cursor context |
| `req` / `session_id` | request and session identity |

## Response

```json
{ "base_resp": { "extra": null, "status_code": 0, "status_message": "" },
  "data": "<candidates>",
  "logid": "0217…",
  "use_time": 6 }
```

`base_resp.status_code = 0` means success; `data` is empty when there are no
candidates; `logid` is the correlation id for support.

## HTTPS is not a fallback

The same paths exist over HTTPS on `ime.doubao.com` and the CDN, but they are a
different backend: candidate routes do not compute there. Only static resources
such as the hot-word manifest are served over HTTPS, so `gethotword` is the one
route worth calling that way.

## Integration notes

1. **Resolution** — the domain may not resolve directly on some networks; use a
   reliable resolver.
2. **Keepalive** — application-level ping around every 3 s.
3. **Concurrency** — open a stream per request rather than serialising on one.
4. **Failure** — silent fallback to local candidates; never surface an error.
5. **Credentials** — treat the certificate as replaceable configuration, not a
   compile-time constant.

## Out of scope

The inner payload of the candidate route (`ime-cloud`) is not defined here.
Integration of candidate ranking needs that structure from the service provider;
until then the association route is the usable half of the channel.
