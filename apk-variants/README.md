# Send-sheet entrance — A/B builds (2026-06-11)

Two installable APKs of the send bottom-sheet entrance animation, kept so you can
install either and compare on-device. Both are the same app/package
(`dev.superdrop.debug`) — installing one replaces the other.

- **super-drop-entrance-A-fullwidth-heightonly.apk**
  Card stays FULL WIDTH and grows in HEIGHT only (scaleY), expanding + a small
  vertical overshoot/bounce as ONE motion. Trade-off: the content is vertically
  SQUISHED during the grow (full width, half height) until it grows out.
  (= git `1cbc2d6`; this is also the current state of the code / `super-drop-debug.apk`.)

- **super-drop-entrance-B-reveal-unfold.apk**
  Card is laid out full-size (content UNDISTORTED — no squish) and is REVEALED /
  unfolded from the bottom up by an animated clip. Trade-offs: no separate top-edge
  bounce (a clip reveal and a scale bounce can't blend into one motion), and the
  rounded TOP corners only appear as the unfold completes (rect clip).
  (= git `ccd8f09`.)

Repo root `super-drop-debug.apk` tracks the latest base: it uses variant A's
**height-only entrance** plus everything merged since (the #200 send-to-Windows fix and
the new "Super Drop" peer-recognition badge). The two `apk-variants/*.apk` files above are
**frozen entrance-only snapshots** for the A/B comparison — neither carries the Super Drop
badge — so installing them compares purely the entrance animation. Install the repo-root
APK for the latest features.
