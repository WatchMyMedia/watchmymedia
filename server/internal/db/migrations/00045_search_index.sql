-- +goose Up
-- Search finds a title by any run of three or more letters in it, whatever
-- their case or accents, through an index: before, every search read every
-- file in the library and the title it belongs to. The index is kept by
-- the triggers below, so nothing that writes a title has to know about it
-- (rows written with the sqlite3 tool are indexed too), and it holds
-- nothing about files: whether a title has a file to play is asked when
-- someone searches.
--
-- Each table's words column is what the index searches: the title and,
-- after a line break, the original title; then, where they have any of the
-- marks people leave out when they type and put no gap for, the same text
-- again without them. Those marks are a hyphen, an apostrophe (straight or
-- curled), a full stop, a middle dot, an asterisk and a slash, so
-- "spiderman" finds "Spider-Man", "oceans" "Ocean's Eleven" and "walle"
-- "WALL-E", with a hyphen or a middle dot.
--
-- The index needs three characters, and the first letter someone types is
-- one. So words has two unit separators (character 31, which no title has)
-- before every word: a word starting with "s" holds the three characters
-- separator, separator, s, and a search of one or two letters asks for
-- those. A word starts at the beginning and after a space, a line break,
-- any of ASCII's punctuation, and the curled quotes, middle dot, dashes,
-- ellipsis, inverted marks and guillemets written below as char(). Only
-- when words that start with the letters do not fill the list does a short
-- search read every title for the letters anywhere. A word after a mark
-- that is not among these is found then, with the letters anywhere; while
-- the list is full of words that start with them, it is not in it until a
-- third letter is typed.
--
-- title and original_title are the table's own, kept beside the words and
-- not indexed: a search with no letters or digits in it reads them.
--
-- searchJoiners and searchMark in internal/api/search_match.go, and
-- searchBreaks in its tests, are the same marks; keep them alike
-- (TestSearchIndexWords checks all three tables).
--
-- An update writes the index only when the title changed: refreshing a show
-- from TMDB updates every episode, nearly always to the title it had.

-- The words of one title while a trigger works them out; empty otherwise.
-- SQLite has no function that takes a list of marks, only replace() for one
-- at a time, and the sqlite3 tool most systems ship cannot read more than
-- about thirty of those inside one another ("parser stack overflow", for
-- every statement on the database). So the marks are written once, here, a
-- dozen to a statement: a title's trigger puts the title in, this
-- table's trigger turns it into the words, and the title's trigger moves
-- them to the index and empties the table.
CREATE TABLE search_scratch (
    id    INTEGER PRIMARY KEY,
    words TEXT NOT NULL
);

-- +goose StatementBegin
CREATE TRIGGER search_scratch_ai AFTER INSERT ON search_scratch BEGIN
    -- The text again without the marks people leave out, when it has any.
    UPDATE search_scratch SET words = words || char(10) ||
        replace(replace(replace(replace(replace(replace(replace(words,
            '-', ''),
            '''', ''),
            char(8217), ''),
            '.', ''),
            char(183), ''),
            '*', ''),
            '/', '')
    WHERE id = new.id AND words <>
        replace(replace(replace(replace(replace(replace(replace(words,
            '-', ''),
            '''', ''),
            char(8217), ''),
            '.', ''),
            char(183), ''),
            '*', ''),
            '/', '');
    -- Two unit separators after everything a word can start after, a dozen
    -- marks to a statement, and before the first word.
    UPDATE search_scratch SET words =
        replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(words,
            ' ', ' ' || char(31, 31)),
            char(10), char(10) || char(31, 31)),
            '!', '!' || char(31, 31)),
            '"', '"' || char(31, 31)),
            '#', '#' || char(31, 31)),
            '$', '$' || char(31, 31)),
            '%', '%' || char(31, 31)),
            '&', '&' || char(31, 31)),
            '''', '''' || char(31, 31)),
            '(', '(' || char(31, 31)),
            ')', ')' || char(31, 31)),
            '*', '*' || char(31, 31))
    WHERE id = new.id;
    UPDATE search_scratch SET words =
        replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(words,
            '+', '+' || char(31, 31)),
            ',', ',' || char(31, 31)),
            '-', '-' || char(31, 31)),
            '.', '.' || char(31, 31)),
            '/', '/' || char(31, 31)),
            ':', ':' || char(31, 31)),
            ';', ';' || char(31, 31)),
            '<', '<' || char(31, 31)),
            '=', '=' || char(31, 31)),
            '>', '>' || char(31, 31)),
            '?', '?' || char(31, 31)),
            '@', '@' || char(31, 31))
    WHERE id = new.id;
    UPDATE search_scratch SET words =
        replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(words,
            '[', '[' || char(31, 31)),
            '\', '\' || char(31, 31)),
            ']', ']' || char(31, 31)),
            '^', '^' || char(31, 31)),
            '_', '_' || char(31, 31)),
            '`', '`' || char(31, 31)),
            '{', '{' || char(31, 31)),
            '|', '|' || char(31, 31)),
            '}', '}' || char(31, 31)),
            '~', '~' || char(31, 31)),
            char(8217), char(8217) || char(31, 31)),
            char(8216), char(8216) || char(31, 31))
    WHERE id = new.id;
    UPDATE search_scratch SET words =
        char(31, 31) || replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(words,
            char(8220), char(8220) || char(31, 31)),
            char(8221), char(8221) || char(31, 31)),
            char(183), char(183) || char(31, 31)),
            char(8211), char(8211) || char(31, 31)),
            char(8212), char(8212) || char(31, 31)),
            char(8230), char(8230) || char(31, 31)),
            char(161), char(161) || char(31, 31)),
            char(191), char(191) || char(31, 31)),
            char(171), char(171) || char(31, 31)),
            char(187), char(187) || char(31, 31))
    WHERE id = new.id;
END;
-- +goose StatementEnd

CREATE VIRTUAL TABLE movies_search USING fts5 (
    title UNINDEXED, original_title UNINDEXED, words,
    tokenize = 'trigram remove_diacritics 1', columnsize = 0
);
INSERT INTO search_scratch (id, words) SELECT id, title || char(10) || COALESCE(original_title, '') FROM movies;
INSERT INTO movies_search (rowid, title, original_title, words)
SELECT t.id, t.title, t.original_title, s.words FROM movies t JOIN search_scratch s ON s.id = t.id;
DELETE FROM search_scratch;

-- +goose StatementBegin
CREATE TRIGGER movies_search_ai AFTER INSERT ON movies BEGIN
    DELETE FROM movies_search WHERE rowid = new.id;
    INSERT INTO search_scratch (id, words) VALUES (new.id, new.title || char(10) || COALESCE(new.original_title, ''));
    INSERT INTO movies_search (rowid, title, original_title, words)
    SELECT new.id, new.title, new.original_title, words FROM search_scratch WHERE id = new.id;
    DELETE FROM search_scratch;
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER movies_search_au AFTER UPDATE OF id, title, original_title ON movies
WHEN old.id IS NOT new.id OR old.title IS NOT new.title OR old.original_title IS NOT new.original_title BEGIN
    DELETE FROM movies_search WHERE rowid = old.id;
    DELETE FROM movies_search WHERE rowid = new.id;
    INSERT INTO search_scratch (id, words) VALUES (new.id, new.title || char(10) || COALESCE(new.original_title, ''));
    INSERT INTO movies_search (rowid, title, original_title, words)
    SELECT new.id, new.title, new.original_title, words FROM search_scratch WHERE id = new.id;
    DELETE FROM search_scratch;
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER movies_search_ad AFTER DELETE ON movies BEGIN
    DELETE FROM movies_search WHERE rowid = old.id;
END;
-- +goose StatementEnd

CREATE VIRTUAL TABLE shows_search USING fts5 (
    title UNINDEXED, original_title UNINDEXED, words,
    tokenize = 'trigram remove_diacritics 1', columnsize = 0
);
INSERT INTO search_scratch (id, words) SELECT id, title || char(10) || COALESCE(original_title, '') FROM shows;
INSERT INTO shows_search (rowid, title, original_title, words)
SELECT t.id, t.title, t.original_title, s.words FROM shows t JOIN search_scratch s ON s.id = t.id;
DELETE FROM search_scratch;

-- +goose StatementBegin
CREATE TRIGGER shows_search_ai AFTER INSERT ON shows BEGIN
    DELETE FROM shows_search WHERE rowid = new.id;
    INSERT INTO search_scratch (id, words) VALUES (new.id, new.title || char(10) || COALESCE(new.original_title, ''));
    INSERT INTO shows_search (rowid, title, original_title, words)
    SELECT new.id, new.title, new.original_title, words FROM search_scratch WHERE id = new.id;
    DELETE FROM search_scratch;
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER shows_search_au AFTER UPDATE OF id, title, original_title ON shows
WHEN old.id IS NOT new.id OR old.title IS NOT new.title OR old.original_title IS NOT new.original_title BEGIN
    DELETE FROM shows_search WHERE rowid = old.id;
    DELETE FROM shows_search WHERE rowid = new.id;
    INSERT INTO search_scratch (id, words) VALUES (new.id, new.title || char(10) || COALESCE(new.original_title, ''));
    INSERT INTO shows_search (rowid, title, original_title, words)
    SELECT new.id, new.title, new.original_title, words FROM search_scratch WHERE id = new.id;
    DELETE FROM search_scratch;
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER shows_search_ad AFTER DELETE ON shows BEGIN
    DELETE FROM shows_search WHERE rowid = old.id;
END;
-- +goose StatementEnd

CREATE VIRTUAL TABLE episodes_search USING fts5 (
    title UNINDEXED, words,
    tokenize = 'trigram remove_diacritics 1', columnsize = 0
);
INSERT INTO search_scratch (id, words) SELECT id, title FROM episodes;
INSERT INTO episodes_search (rowid, title, words)
SELECT t.id, t.title, s.words FROM episodes t JOIN search_scratch s ON s.id = t.id;
DELETE FROM search_scratch;

-- +goose StatementBegin
CREATE TRIGGER episodes_search_ai AFTER INSERT ON episodes BEGIN
    DELETE FROM episodes_search WHERE rowid = new.id;
    INSERT INTO search_scratch (id, words) VALUES (new.id, new.title);
    INSERT INTO episodes_search (rowid, title, words)
    SELECT new.id, new.title, words FROM search_scratch WHERE id = new.id;
    DELETE FROM search_scratch;
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER episodes_search_au AFTER UPDATE OF id, title ON episodes
WHEN old.id IS NOT new.id OR old.title IS NOT new.title BEGIN
    DELETE FROM episodes_search WHERE rowid = old.id;
    DELETE FROM episodes_search WHERE rowid = new.id;
    INSERT INTO search_scratch (id, words) VALUES (new.id, new.title);
    INSERT INTO episodes_search (rowid, title, words)
    SELECT new.id, new.title, words FROM search_scratch WHERE id = new.id;
    DELETE FROM search_scratch;
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER episodes_search_ad AFTER DELETE ON episodes BEGIN
    DELETE FROM episodes_search WHERE rowid = old.id;
END;
-- +goose StatementEnd

-- +goose Down
DROP TRIGGER episodes_search_ad;
DROP TRIGGER episodes_search_au;
DROP TRIGGER episodes_search_ai;
DROP TABLE episodes_search;
DROP TRIGGER shows_search_ad;
DROP TRIGGER shows_search_au;
DROP TRIGGER shows_search_ai;
DROP TABLE shows_search;
DROP TRIGGER movies_search_ad;
DROP TRIGGER movies_search_au;
DROP TRIGGER movies_search_ai;
DROP TABLE movies_search;
DROP TRIGGER search_scratch_ai;
DROP TABLE search_scratch;
