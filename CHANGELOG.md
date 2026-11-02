## 0.3.1

### Apache License 2.0

This repository is public and is what integrators read, and it shipped without
a licence. Nobody could use it: no licence means all rights reserved, so a
company's legal review would have stopped an integration whatever the README
promised. The published POM has claimed `Apache-2.0`
since the first release, with no file in the repository to back the claim; now
the two agree.

The licence is Apache 2.0, the full text is in `LICENSE`, and the README says
so. Nothing about the code or the API has changed in this release.

## 0.2.0

### Phone numbers: the server decides, not the SDK

The client-side check was strict E.164 (`^\+[1-9]\d{6,14}$`). It was wrong in
both directions, and the second direction is the one that hurt:

* It **refused numbers the API accepts.** Someone typing `08031234567`, or
  pasting `0803 123 4567` out of their contacts, got a client-side error for a
  number the platform would have taken. Only the full `+234...` form worked.
* It **accepted numbers the API refuses**, such as a UK mobile or a Nigerian
  landline, so the SDK let a call to `create` through and the server refused it.

The check is now deliberately light: non-empty, digits, with an optional leading
plus. Separators (spaces, brackets, hyphens, dots, non-breaking spaces, Unicode
dashes) are stripped using the same set the server strips, so the SDK cannot
reject a value the API would accept. The server owns the rule, reads it from its
countries table, and can change it without a new SDK release.

### Two new typed errors

The server's refusals now arrive as types you can catch, rather than a generic
validation failure:

* `NumberNotSupported` for a number Relavoi does not connect. Today that means
  anything other than a Nigerian mobile.
* `CrossBorderNotSupported` when the two parties are not in the same country.

Both are matched on the problem document's `type` slug, so they work against any
environment. Neither message contains the number that was refused.

### No part of a phone number in any message or log line

Several places leaked digits, and one leaked whole numbers:

* The validation error appended the **last four digits** of the rejected value.
  Removed. The message now names the field and the expected shape, nothing else.
* Request logging wrote the **full URL**, and `/sessions/verify` carries the
  subscriber's number in its query string. With SDK logging on, that put a
  plaintext phone number in logcat. Only the path is logged now.
* `maskPhone` returned `+****5678`, and those last four digits were logged on
  session creation, dialling, presence updates and push registration. The
  function is gone and those lines now carry the session id, which is the key
  that actually correlates them.
* Error responses no longer surface a **raw body**. Only `type` and `detail` are
  read from a problem document. An error page from in front of the API commonly
  echoes the request URI, which is how a number would have reached a crash
  reporter. `ApiError.statusCode` is unchanged; `ApiError.body` now carries the
  problem `detail` or null.

### Possibly breaking

* `ApiError.body` no longer contains the raw response body. The status code is
  unchanged.
* `PhoneUtils` is internal, but if you reached it: `requireValidE164` is now
  `requireNumberShaped` and returns the canonical form, `validateE164` is now
  `looksLikeNumber`, and `maskPhone` is removed.
* Numbers are sent with separators stripped. `0803 123 4567` goes out as
  `08031234567`.

## 0.1.0

* First release: auth, sessions, call verification, push registration,
  WebSocket events, presence and the offline queue.
