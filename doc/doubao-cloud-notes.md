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

  These observations establish the vendor client's connection path. Independent integration requires service credentials issued for that integration.

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
| `ime-cloud` | exists → `ConvertByJson` / `ConvertByPB` (observed protobuf described below) |
| `ime-cloud-word_association` | exists, accepts the documented field set |
| `ime-cloud-prefetch_ctx_regulator` | exists, answers `"forced success"` |

## Android 1.5.6 conversion payload

The signed Android 1.5.6 keyboard sends an envelope with `aid=ime-cloud` and a
Base64-encoded protobuf in `data`. The following tags were observed in the
inner request while typing in a dedicated synthetic editor.

| Tag | Wire type | Observed value |
|---|---|---|
| 2 | varint | Present in nine-key requests. Value 1 in the sampled nine-key gestures and ordinary requests. |
| 3 | length-delimited | The input prefix. Nine-key requests contain the digits, with an apostrophe at an explicit syllable boundary. Full-key requests contain letters. |
| 4 | repeated length-delimited | Sixteen records in each sampled request immediately following a reading choice or separator tap. |
| 7 | length-delimited | The selected reading after a reading choice. For example, tag 3 remains `9426` while tag 7 is `xian`. |
| 8 | length-delimited | Literal `1.0.0` in the sampled nine-key requests. |
| 9 | varint | Value 12 in the sampled ordinary inputs. Omitted in the requests immediately following the two gestures. |

An independent encoder reproduced the complete inner protobuf of six observed
ordinary nine-key requests byte for byte. They carried tags 2, 3, 8 and 9.
That test covered `9`, `94`, `942`, `9426`, `94'2`, and `94'26`.

In the sampled gesture requests, several tag-4 records matched candidate text in
child tag 1. The recorded test matches include `先`, `线`, and `现` after selecting
`xian`, and `一`, `已`, and `以` after tapping the separator. Child tag 2 had two
bytes that remain uninterpreted. Child tag 3 was zero and child tag 4 was an
empty length-delimited field. The samples do not establish how the service uses
the records or the literals in tags 8 and 9.

The observed conversion response contains Base64-encoded protobuf in `Data`.
In a synthetic long sentence, child tag 4 of a repeated top-level tag 3 record
matched the complete candidate text. Association responses and unclassified
stream calls were observed separately.

Earlier trials with guessed JSON containers, field capitalization, and the
association request layout returned conversion errors.

These values describe the signed app at its SDK boundary. Integrating this
channel still requires service access issued for this project and validation
of the remaining gesture and response fields.

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
| ASR session `extra` | multi-pass settings are sent; original post-stop full-text refinement remains unverified in this app |
| release-frame `force_asr_twopass` + `finish_audio` | **shipped here** |
| user words / NER / correction-pair uploads | request adapters exist; the observed route returned `403 路由已封禁`; account integration remains unverified |
| cloud pinyin candidates | ordinary request format verified; service authorization and gesture handling pending |
| cloud hot-words | blocked on the engine's dictionary reader |
