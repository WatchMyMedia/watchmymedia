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
-- one. So words has two unit separators (character 31, which no title has;
-- x'1f1f' below) at its start and after every space and line break (x'0a'):
-- a word starting with "s" holds the three characters separator,
-- separator, s, and a search of one letter asks for those. A search of two
-- letters also asks for them after a hyphen, a bracket and the other marks
-- a word can follow, which are three characters with the mark and need
-- nothing kept here.
--
-- title and original_title are the table's own, kept beside the words and
-- not indexed: a search with no letters or digits in it reads them.
--
-- The triggers are small on purpose. SQLite compiles a table's triggers
-- into every statement that writes to it, each time the statement is
-- prepared, whether or not they fire. A first version worked the words out
-- in a helper table's trigger, with some fifty replace() calls for every
-- mark a word can follow: every write of a title, changed or not, cost six
-- times what it had (245 against 42 microseconds; 8.8 against 1.1 ms under
-- Go's race detector, where the matcher's writers then waited for each
-- other past the 5 s limit). Each replace() here costs every title write
-- about a microsecond, and a blob literal less than a call to char().
--
-- searchJoiners and searchMark in internal/api/search_match.go are the
-- same marks; keep them alike (TestSearchIndexWords checks every copy
-- below, for all three tables).
--
-- An update writes the index only when the title changed: refreshing a show
-- from TMDB updates every episode, nearly always to the title it had.

CREATE VIRTUAL TABLE movies_search USING fts5 (
    title UNINDEXED, original_title UNINDEXED, words,
    tokenize = 'trigram remove_diacritics 1', columnsize = 0
);
INSERT INTO movies_search (rowid, title, original_title, words)
SELECT id, title, original_title,
    x'1f1f' || replace(replace(
        title || x'0a' || COALESCE(original_title, '') || CASE WHEN (title || x'0a' || COALESCE(original_title, '')) GLOB ('*[-''.*/' || char(8217, 183) || ']*')
            THEN x'0a' || replace(replace(replace(replace(replace(replace(replace(title || x'0a' || COALESCE(original_title, ''), '-', ''), '''', ''), char(8217), ''), '.', ''), char(183), ''), '*', ''), '/', '')
            ELSE '' END,
        ' ', ' ' || x'1f1f'), x'0a', x'0a' || x'1f1f')
FROM movies;

-- +goose StatementBegin
CREATE TRIGGER movies_search_ai AFTER INSERT ON movies BEGIN
    INSERT OR REPLACE INTO movies_search (rowid, title, original_title, words)
    VALUES (new.id, new.title, new.original_title,
        x'1f1f' || replace(replace(
            new.title || x'0a' || COALESCE(new.original_title, '') || CASE WHEN (new.title || x'0a' || COALESCE(new.original_title, '')) GLOB ('*[-''.*/' || char(8217, 183) || ']*')
                THEN x'0a' || replace(replace(replace(replace(replace(replace(replace(new.title || x'0a' || COALESCE(new.original_title, ''), '-', ''), '''', ''), char(8217), ''), '.', ''), char(183), ''), '*', ''), '/', '')
                ELSE '' END,
            ' ', ' ' || x'1f1f'), x'0a', x'0a' || x'1f1f'));
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER movies_search_au AFTER UPDATE OF id, title, original_title ON movies
WHEN old.id IS NOT new.id OR old.title IS NOT new.title OR old.original_title IS NOT new.original_title BEGIN
    DELETE FROM movies_search WHERE rowid = old.id;
    INSERT OR REPLACE INTO movies_search (rowid, title, original_title, words)
    VALUES (new.id, new.title, new.original_title,
        x'1f1f' || replace(replace(
            new.title || x'0a' || COALESCE(new.original_title, '') || CASE WHEN (new.title || x'0a' || COALESCE(new.original_title, '')) GLOB ('*[-''.*/' || char(8217, 183) || ']*')
                THEN x'0a' || replace(replace(replace(replace(replace(replace(replace(new.title || x'0a' || COALESCE(new.original_title, ''), '-', ''), '''', ''), char(8217), ''), '.', ''), char(183), ''), '*', ''), '/', '')
                ELSE '' END,
            ' ', ' ' || x'1f1f'), x'0a', x'0a' || x'1f1f'));
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
INSERT INTO shows_search (rowid, title, original_title, words)
SELECT id, title, original_title,
    x'1f1f' || replace(replace(
        title || x'0a' || COALESCE(original_title, '') || CASE WHEN (title || x'0a' || COALESCE(original_title, '')) GLOB ('*[-''.*/' || char(8217, 183) || ']*')
            THEN x'0a' || replace(replace(replace(replace(replace(replace(replace(title || x'0a' || COALESCE(original_title, ''), '-', ''), '''', ''), char(8217), ''), '.', ''), char(183), ''), '*', ''), '/', '')
            ELSE '' END,
        ' ', ' ' || x'1f1f'), x'0a', x'0a' || x'1f1f')
FROM shows;

-- +goose StatementBegin
CREATE TRIGGER shows_search_ai AFTER INSERT ON shows BEGIN
    INSERT OR REPLACE INTO shows_search (rowid, title, original_title, words)
    VALUES (new.id, new.title, new.original_title,
        x'1f1f' || replace(replace(
            new.title || x'0a' || COALESCE(new.original_title, '') || CASE WHEN (new.title || x'0a' || COALESCE(new.original_title, '')) GLOB ('*[-''.*/' || char(8217, 183) || ']*')
                THEN x'0a' || replace(replace(replace(replace(replace(replace(replace(new.title || x'0a' || COALESCE(new.original_title, ''), '-', ''), '''', ''), char(8217), ''), '.', ''), char(183), ''), '*', ''), '/', '')
                ELSE '' END,
            ' ', ' ' || x'1f1f'), x'0a', x'0a' || x'1f1f'));
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER shows_search_au AFTER UPDATE OF id, title, original_title ON shows
WHEN old.id IS NOT new.id OR old.title IS NOT new.title OR old.original_title IS NOT new.original_title BEGIN
    DELETE FROM shows_search WHERE rowid = old.id;
    INSERT OR REPLACE INTO shows_search (rowid, title, original_title, words)
    VALUES (new.id, new.title, new.original_title,
        x'1f1f' || replace(replace(
            new.title || x'0a' || COALESCE(new.original_title, '') || CASE WHEN (new.title || x'0a' || COALESCE(new.original_title, '')) GLOB ('*[-''.*/' || char(8217, 183) || ']*')
                THEN x'0a' || replace(replace(replace(replace(replace(replace(replace(new.title || x'0a' || COALESCE(new.original_title, ''), '-', ''), '''', ''), char(8217), ''), '.', ''), char(183), ''), '*', ''), '/', '')
                ELSE '' END,
            ' ', ' ' || x'1f1f'), x'0a', x'0a' || x'1f1f'));
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
INSERT INTO episodes_search (rowid, title, words)
SELECT id, title,
    x'1f1f' || replace(replace(
        title || CASE WHEN (title) GLOB ('*[-''.*/' || char(8217, 183) || ']*')
            THEN x'0a' || replace(replace(replace(replace(replace(replace(replace(title, '-', ''), '''', ''), char(8217), ''), '.', ''), char(183), ''), '*', ''), '/', '')
            ELSE '' END,
        ' ', ' ' || x'1f1f'), x'0a', x'0a' || x'1f1f')
FROM episodes;

-- +goose StatementBegin
CREATE TRIGGER episodes_search_ai AFTER INSERT ON episodes BEGIN
    INSERT OR REPLACE INTO episodes_search (rowid, title, words)
    VALUES (new.id, new.title,
        x'1f1f' || replace(replace(
            new.title || CASE WHEN (new.title) GLOB ('*[-''.*/' || char(8217, 183) || ']*')
                THEN x'0a' || replace(replace(replace(replace(replace(replace(replace(new.title, '-', ''), '''', ''), char(8217), ''), '.', ''), char(183), ''), '*', ''), '/', '')
                ELSE '' END,
            ' ', ' ' || x'1f1f'), x'0a', x'0a' || x'1f1f'));
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER episodes_search_au AFTER UPDATE OF id, title ON episodes
WHEN old.id IS NOT new.id OR old.title IS NOT new.title BEGIN
    DELETE FROM episodes_search WHERE rowid = old.id;
    INSERT OR REPLACE INTO episodes_search (rowid, title, words)
    VALUES (new.id, new.title,
        x'1f1f' || replace(replace(
            new.title || CASE WHEN (new.title) GLOB ('*[-''.*/' || char(8217, 183) || ']*')
                THEN x'0a' || replace(replace(replace(replace(replace(replace(replace(new.title, '-', ''), '''', ''), char(8217), ''), '.', ''), char(183), ''), '*', ''), '/', '')
                ELSE '' END,
            ' ', ' ' || x'1f1f'), x'0a', x'0a' || x'1f1f'));
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
