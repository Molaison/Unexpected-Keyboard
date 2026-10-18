# Doubao cloud channel — transport map and open blockers

Notes from reverse engineering `doubaoime 1.5.1`
(`abb24d0a33cee91a9d96783243b75950151bc478ce47da629e5fdf8844607f26`).

This file records what was established about the cloud pinyin / association
channel, so nobody has to re-derive it. It is documentation only; nothing here
is executed by the keyboard.

## Two different services answer the same paths

| host | what it is | behaviour |
|---|---|---|
| `ime-gw3.oceancloudapi.com` | the real gateway, **QUIC only** (UDP/443) | parses the request and returns candidates or a business error |
| `ime.doubao.com`, `ime.oceancloudapi.com` (CDN) | HTTPS edge | answers `200 {"Data":""}` for any body, including `{}` |

So the HTTPS route is a dead end for `convert`: it is not a fallback, it simply
does not compute. `gethotword` does work over HTTPS on the edge, because it only
hands out a pre-signed download URL.

## QUIC gateway

* Real addresses come from public DNS; local resolution of
  `*.oceancloudapi.com` is intercepted to unroutable `28.0.1.x`.
  `ime-gw3` → `122.14.229.70`, `101.126.56.170`.
* **ALPN is `h3`.** Every other candidate is refused during the handshake.
* **Client certificate is mandatory.** TCP/443 accepts the connection and then
  drops it (`EOF occurred in violation of protocol`), whatever certificate is
  presented. Over UDP the handshake only completes with the certificate that is
  embedded in `libime_net_sdk.so`:

  | block in `.so` | identity |
  |---|---|
  | 1st PEM | `CN=RapidSSL TLS RSA CA G1` ← `CN=DigiCert Global Root G2` (server CA) |
  | 2nd PEM | **`CN=ImeNetSDK`, issuer `CN=Bytedance`** — the client certificate |
  | 3rd PEM | the matching RSA-2048 key |

  The server accepts it, so the channel is reproducible outside the vendor SDK.

## Framing

One request per bidirectional stream:

```
stream payload = JSON envelope, where "data" is RAW (unpadded) base64
response       = plain JSON on the same stream, stream ends after it
```

Envelope keys are all lowercase; sending `Data` instead of `data` makes the edge
answer `502`.

```json
{ "aid": "<route>", "version": "<elapsedRealtime>", "data": "<base64>",
  "did": "...", "app_version": "1.5.1", "app_id": "401734", "os_type": "Android" }
```

`aid` selects the route, and an unknown value answers
`{"code":400,"msg":"App.handle[<aid>] not found"}` — an exact oracle.

| route | state |
|---|---|
| `ime-cloud` | exists → `ConvertByJson` / `ConvertByPB` (payload unknown, see below) |
| `ime-cloud-word_association` | exists, accepts the documented field set |
| `ime-cloud-prefetch_ctx_regulator` | exists, answers `"forced success"` |

## The `convert` payload is still unknown

The gateway reports:

```
biz error: ConvertByJson and ConvertByPB both error,
[proto: cannot parse invalid wire-format data]
```

so it accepts both JSON and protobuf, and rejected everything tried:

* 16 container shapes — `{}`, `[]`, bare string, `{"pinyin":...}`,
  `{"comps":[...]}`, `{"cands":[]}`, nested `case/data`, arrays of objects,
  mixed string/number types;
* 3 capitalisation schemes — `round`, `Round`, full PascalCase
  (`Pinyin`/`Input`/…), matching the lowercase keys the `.so` does contain
  (`pinyin`, `keyboard_type`, `input`);
* the field set that `ime-cloud-word_association` *does* accept
  (`Round`, `pinyin`, `extend`, `msg`, `input`, `keyboard_type`, `input_type`,
  `rank_version`, `rounds_history`, `text_before_cursor`, `text_after_cursor`,
  `req`, `session_id`).

`libkeyboard.so` keeps its symbols (`cloud::CloudModel`,
`InputModel::MakeCloudRequest`, `oime_engine::ConvertParamsIn`, …) and carries all
nine `/obric/ime/cloud/*` URLs, but the request serializer references no literal
field names, and the library links no protobuf runtime — so the schema cannot be
read off the binary.

**Next step:** one capture of a real device request (UDP/443 to `ime-gw3`,
`StreamDataReceived` payload), decode the `data` field, and the schema is known.
Until then `convert` stays unwired; a fabricated payload would just fail.

## Hot-word dictionary is engine-private

`gethotword?version=0` returns a pre-signed URL to `cloud_hot_word.dat`
(67 312 bytes, MD5 checked). The file is not text and not compressed:

* entropy 5.49 bit/byte, all 256 byte values present → structured binary;
* no UTF-8 or UTF-16 CJK runs at all;
* header decodes as little-endian `uint32`: `12010`, `20230413` (a date),
  then offsets/sizes including `48` and `67312` (the file size).

It is a compiled dictionary in the same family as `assets/dict/*` in the APK, so
it needs `libkeyboard.so`'s reader to decode. Downloading it is therefore not
useful on its own; `DoubaoCloudPorts.fetchHotword()` returns the metadata and the
URL, and a caller with the engine available could use it.

## Consequences for this app

| capability | status |
|---|---|
| cursor/package context in the session | **shipped here** |
| full `extra` field set | **shipped here** |
| release-frame `force_asr_twopass` + `finish_audio` | **shipped here** |
| user words / NER / correction-pair uploads | implemented, but the routes answer `403 路由已封禁` until a Doubao account login exists |
| cloud pinyin candidates | blocked on the capture above |
| cloud hot-words | blocked on the engine's dictionary reader |
