# Golden replies

Replies captured from a real server, for `GoldenRepliesTest`: each is decoded
by the generated client in `core:api` and mapped as the app maps it, and again
with every field the generated model marks optional taken out, as the oldest
server the client accepts would send it. The hand-written replies in
`../fixtures/` test the mapping's corners; these catch the client and the
server drifting apart.

They come from the fixture library (`make fixture`), so the titles are made
up. The fixture itself is unmatched, so the script gives a copy of its
database made-up metadata (one show with two seasons, four movies, some watch
progress), runs the server from this checkout on that copy on 127.0.0.1, signs
in as `viewer` and saves the replies. Tokens, session IDs, play IDs and the
pairing code are replaced with fixed values, and the media folder is
`/media`.

Regenerate them after a server change the app reads, from the repository
root, then run the tests:

```bash
deploy/fixture/golden-replies.sh
cd android && ./gradlew :core:data:testDebugUnitTest --tests '*GoldenReplies*'
```

`FIXTURE_DIR` points it at another fixture (a worktree has none of its own:
`FIXTURE_DIR=<main checkout>/data/fixture`), and `GOLDEN_PORT` picks another
port than 56487. A new reply needs a line in `GoldenRepliesTest.MODELS`; a
test fails until it has one.
