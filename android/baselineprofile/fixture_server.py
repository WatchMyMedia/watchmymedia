#!/usr/bin/env python3
"""A throwaway fixture server for the real-build journeys and benchmarks.

    python3 -I android/baselineprofile/fixture_server.py [--port 28130]

Copies the fixture library's database (deploy/fixture/fixture.sh: `make
fixture`, then `make fixture-serve` once so the database and its accounts
exist) into a temporary folder, gives the copy its own server id and name,
gives each movie file a page with a poster, and runs the server on
127.0.0.1:PORT until stopped (Ctrl-C, or SIGTERM from generate.sh), when the
copy is deleted. An emulator reaches it at 10.0.2.2:PORT.

The fixture's titles are made up and never matched on TMDB, so it has no
pages and no artwork. Here each movie file becomes a matched movie (by hand,
as a person would with Fix match) and gets a test pattern as its poster
through the admin's upload route, so Home and the Movies grid load real
images through the app's image loader. The patterns are drawn by ffmpeg:
nothing is under copyright. The fixture's own folder is never written to.

It prints "ready <address>" when the server is up. The server's own log is
written to server.log in the temporary folder, at debug level, so every
request is in it; --log copies it somewhere kept when the server stops.
"""

import argparse
import http.cookiejar
import json
import os
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ADMIN = ("admin", "fixture-admin-1")
MOVIE_FILE = re.compile(r"^(?P<title>.+) \((?P<year>\d{4})\)\.\w+$")
# A tmdb_id no real film has: the fixture's movies are matched to nothing.
FIRST_TMDB_ID = 990_000_001
# Drawn from the gradients source, one look per movie.
COLOURS = [("0x1e3a8a", "0xf59e0b"), ("0x065f46", "0xfde68a"), ("0x7c2d12", "0x93c5fd"), ("0x581c87", "0xa7f3d0"), ("0x0f172a", "0xfca5a5")]


def copy_database(fixture_db, data_dir):
    """A copy taken through SQLite's backup, as `sqlite3 … ".backup …"` does, with its own id and name."""
    target = os.path.join(data_dir, "watchmymedia.db")
    with sqlite3.connect(f"file:{fixture_db}?mode=ro", uri=True) as src, sqlite3.connect(target) as dst:
        src.backup(dst)
    with sqlite3.connect(target) as db:
        # Copies of one database count as one server to the apps.
        db.execute("INSERT OR REPLACE INTO settings(key, value) VALUES ('server.id', 'profile-' || lower(hex(randomblob(8))))")
        db.execute("INSERT OR REPLACE INTO settings(key, value) VALUES ('server.name', 'Profile Fixture')")
    return target


def give_movies_pages(db_path):
    """Matches each movie file to a movie of its own name and year; returns the movies' ids by title."""
    movies = {}
    with sqlite3.connect(db_path) as db:
        rows = db.execute(
            "SELECT f.id, f.library_id, f.path FROM media_files f JOIN libraries l ON l.id = f.library_id "
            "WHERE l.type = 'movie' AND f.extra_kind IS NULL ORDER BY f.id"
        ).fetchall()
        for file_id, library_id, path in rows:
            match = MOVIE_FILE.match(os.path.basename(path))
            if not match:
                continue
            title, year = match.group("title"), int(match.group("year"))
            key = (library_id, title, year)
            if key not in movies:
                existing = db.execute("SELECT id FROM movies WHERE library_id = ? AND title = ? AND year = ?", key).fetchone()
                if existing:
                    movies[key] = existing[0]
                else:
                    cur = db.execute(
                        "INSERT INTO movies (library_id, tmdb_id, title, year, release_date, overview, runtime_min, genres) "
                        "VALUES (?, ?, ?, ?, ?, ?, 3, '[\"Drama\"]')",
                        (library_id, FIRST_TMDB_ID + len(movies), title, year, f"{year}-06-01", f"{title}, a film of the fixture library."),
                    )
                    movies[key] = cur.lastrowid
            db.execute(
                "UPDATE media_files SET movie_id = ?, match_status = 'matched', match_locked = 1 WHERE id = ?",
                (movies[key], file_id),
            )
    return {title: movie_id for (_, title, _), movie_id in movies.items()}


def wait_for(base, timeout_s=60):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base + "/api/health", timeout=2):
                return
        except OSError:
            time.sleep(0.3)
    raise SystemExit(f"the server at {base} did not come up")


def poster(ffmpeg, folder, n):
    """A 500×750 test pattern, a different look for each [n]."""
    out = os.path.join(folder, f"poster-{n}.jpg")
    c0, c1 = COLOURS[n % len(COLOURS)]
    subprocess.run(
        [ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
         "-i", f"gradients=s=500x750:c0={c0}:c1={c1}:seed={n + 1}:duration=1", "-frames:v", "1", "-q:v", "3", out],
        check=True,
    )
    return out


def upload_posters(base, movies, folder, ffmpeg):
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    login = urllib.request.Request(
        base + "/api/auth/login",
        data=json.dumps({"name": ADMIN[0], "password": ADMIN[1]}).encode(),
        headers={"Content-Type": "application/json"},
    )
    opener.open(login, timeout=10).close()
    for n, (title, movie_id) in enumerate(sorted(movies.items())):
        with open(poster(ffmpeg, folder, n), "rb") as f:
            body = f.read()
        upload = urllib.request.Request(
            f"{base}/api/movies/{movie_id}/poster/upload", data=body, headers={"Content-Type": "application/octet-stream"}
        )
        opener.open(upload, timeout=30).close()
        print(f"poster for {title} (movie {movie_id})", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--port", type=int, default=28130)
    parser.add_argument("--fixture", default=os.environ.get("FIXTURE_DIR", os.path.join(ROOT, "data", "fixture")))
    parser.add_argument("--server", default=os.path.join(ROOT, "bin", "watchmymedia"))
    parser.add_argument("--ffmpeg", default=os.environ.get("FFMPEG", "ffmpeg"))
    parser.add_argument("--log", help="where to copy the server's log when it stops")
    args = parser.parse_args()

    fixture_db = os.path.join(args.fixture, "watchmymedia.db")
    if not os.access(args.server, os.X_OK):
        raise SystemExit(f"no server at {args.server}: make build")
    if not os.path.exists(fixture_db):
        raise SystemExit(f"no fixture database at {fixture_db}: make fixture, then make fixture-serve once")

    data_dir = tempfile.mkdtemp(prefix="wmm-profile-fixture-")
    server = None

    def stop(*_):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, stop)
    try:
        db_path = copy_database(fixture_db, data_dir)
        movies = give_movies_pages(db_path)
        env = dict(
            os.environ,
            WATCHMYMEDIA_DATA_DIR=data_dir,
            WATCHMYMEDIA_PORT=str(args.port),
            WATCHMYMEDIA_HOST="127.0.0.1",
            WATCHMYMEDIA_DISCOVERY="false",
            WATCHMYMEDIA_LOG_LEVEL="debug",
            WATCHMYMEDIA_LOG_FORMAT="json",
        )
        with open(os.path.join(data_dir, "server.log"), "wb") as log:
            server = subprocess.Popen([args.server, "serve"], env=env, stdout=log, stderr=subprocess.STDOUT)
        base = f"http://127.0.0.1:{args.port}"
        wait_for(base)
        upload_posters(base, movies, data_dir, args.ffmpeg)
        print(f"ready 127.0.0.1:{args.port} (10.0.2.2:{args.port} from an emulator), {len(movies)} movies with posters", flush=True)
        server.wait()
    except KeyboardInterrupt:
        pass
    finally:
        if server and server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=20)
            except subprocess.TimeoutExpired:
                server.kill()
        if args.log and os.path.exists(os.path.join(data_dir, "server.log")):
            shutil.copyfile(os.path.join(data_dir, "server.log"), args.log)
        shutil.rmtree(data_dir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
