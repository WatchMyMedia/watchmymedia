#!/usr/bin/env python3
"""Synthetic media only, in this worktree's ignored Samsung prototype directory."""
import argparse
import json
from pathlib import Path
import sqlite3
import subprocess

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'data/samsung-prototype'
MEDIA = DATA / 'media/Movies'
MARKER = DATA / '.samsung-prototype-fixture'

SAMPLES = [
    ('Signal Garden (2026)', 'mp4', 'libx264', 'aac'),
    ('Copper Sky (2026)', 'mkv', 'libx264', 'aac'),
    ('Velvet Current (2026)', 'mkv', 'libx265', 'eac3'),
]


def render():
    if DATA.exists() and not MARKER.exists():
        raise SystemExit('Refusing to use an existing directory without the prototype marker.')
    DATA.mkdir(parents=True, exist_ok=True)
    MARKER.write_text('Synthetic Samsung prototype fixture only.\n')
    for title, container, video, audio in SAMPLES:
        folder = MEDIA / title
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / f'{title}.{container}'
        subtitle = folder / f'{title}.en.srt'
        subtitle.write_text('1\n00:00:02,000 --> 00:00:06,000\nSamsung prototype: two seconds.\n\n'
                            '2\n00:00:20,000 --> 00:00:24,000\nSamsung prototype: twenty seconds.\n\n'
                            '3\n00:00:40,000 --> 00:00:44,000\nSamsung prototype: forty seconds.\n')
        if path.exists():
            continue
        temporary = folder / f'{title}.part'
        codec = ['-c:v', video, '-preset', 'ultrafast', '-g', '48', '-pix_fmt', 'yuv420p']
        if video == 'libx265':
            codec += ['-x265-params', 'log-level=error:pools=2', '-tag:v', 'hvc1']
        subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
                        '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=24',
                        '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
                        '-t', '60', *codec, '-c:a', audio, '-ac', '2', '-b:a', '192k',
                        *(['-movflags', '+faststart'] if container == 'mp4' else []),
                        '-f', 'mp4' if container == 'mp4' else 'matroska', str(temporary)], check=True)
        temporary.rename(path)
        print(f'Created synthetic sample: {title}')
    config = DATA / 'config.yaml'
    if not config.exists():
        config.write_text('host: 127.0.0.1\nport: 27686\nlibraries:\n'
                          f'  movies: {json.dumps(str(MEDIA))}\n'
                          'network:\n  discovery: false\nscan:\n  detect_segments: false\n')
    print('Fixture is ready. Start the isolated server, then run this script with --seed.')


def seed():
    """Attach fictional metadata locally so progress/resume has a movie to own it."""
    if not MARKER.is_file():
        raise SystemExit('Prototype marker missing; refusing database access.')
    database = DATA / 'watchmymedia.db'
    if not database.is_file():
        raise SystemExit('Start the isolated server and let its scanner finish first.')
    with sqlite3.connect(database) as db:
        rows = db.execute('SELECT id,library_id,path FROM media_files').fetchall()
        expected = {(MEDIA / title / f'{title}.{container}').resolve() for title, container, *_ in SAMPLES}
        if len(rows) != len(SAMPLES) or {Path(row[2]).resolve() for row in rows} != expected:
            raise SystemExit('Expected exactly the three prototype samples; wait for the scanner.')
        for file_id, library_id, path in rows:
            title = Path(path).stem.removesuffix(' (2026)')
            # Negative fixture-only metadata identifiers never resolve to real TMDB media.
            tmdb_id = -file_id
            db.execute('INSERT INTO movies(library_id,tmdb_id,title,year,overview,runtime_min) '
                       'VALUES(?,?,?,?,?,?) ON CONFLICT(library_id,tmdb_id) DO NOTHING',
                       (library_id, tmdb_id, title, 2026, 'Synthetic Samsung playback fixture.', 1))
            movie_id = db.execute('SELECT id FROM movies WHERE library_id=? AND tmdb_id=?', (library_id, tmdb_id)).fetchone()[0]
            db.execute("UPDATE media_files SET movie_id=?,match_status='matched',match_locked=1 WHERE id=?", (movie_id, file_id))
        db.execute("INSERT INTO settings(key,value) VALUES('server.name',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", ('Samsung prototype fixture',))
    print('Seeded fictional metadata for progress and resume. No external metadata service was used.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seed', action='store_true')
    args = parser.parse_args()
    seed() if args.seed else render()
