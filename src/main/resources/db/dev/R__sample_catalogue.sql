-- Development-only, repeatable and non-destructive. Flyway serializes execution.
-- Natural lookups reuse sample parents; conflicts never reset availability.
INSERT INTO tbooker.users (name, email)
VALUES ('Alex Sample', 'alex@example.test'), ('Priya Sample', 'priya@example.test')
ON CONFLICT DO NOTHING;

DO $$
DECLARE
    sample_movie_id BIGINT;
    sample_theatre_id BIGINT;
    sample_screen_id BIGINT;
    sample_show_id BIGINT;
BEGIN
    SELECT id INTO sample_movie_id FROM tbooker.movies
    WHERE title = 'The Sample Adventure' AND duration_minutes = 120
    ORDER BY id LIMIT 1;
    IF sample_movie_id IS NULL THEN
        INSERT INTO tbooker.movies (title, duration_minutes)
        VALUES ('The Sample Adventure', 120) RETURNING id INTO sample_movie_id;
    END IF;

    SELECT id INTO sample_theatre_id FROM tbooker.theatres
    WHERE name = 'Sample Cinema' AND city = 'Bengaluru'
    ORDER BY id LIMIT 1;
    IF sample_theatre_id IS NULL THEN
        INSERT INTO tbooker.theatres (name, city)
        VALUES ('Sample Cinema', 'Bengaluru') RETURNING id INTO sample_theatre_id;
    END IF;

    INSERT INTO tbooker.screens (theatre_id, name)
    VALUES (sample_theatre_id, 'Screen 1') ON CONFLICT (theatre_id, name) DO NOTHING;
    SELECT id INTO sample_screen_id FROM tbooker.screens
    WHERE theatre_id = sample_theatre_id AND name = 'Screen 1';

    INSERT INTO tbooker.seats (screen_id, seat_number)
    SELECT sample_screen_id, 'A' || n FROM generate_series(1, 8) AS n
    ON CONFLICT (screen_id, seat_number) DO NOTHING;

    INSERT INTO tbooker.movie_shows (movie_id, screen_id, start_time)
    VALUES (sample_movie_id, sample_screen_id, TIMESTAMPTZ '2030-01-01 18:00:00+00')
    ON CONFLICT (screen_id, start_time) DO NOTHING;
    SELECT id INTO sample_show_id FROM tbooker.movie_shows
    WHERE screen_id = sample_screen_id AND start_time = TIMESTAMPTZ '2030-01-01 18:00:00+00';

    INSERT INTO tbooker.show_seats (show_id, seat_id, screen_id, status)
    SELECT sample_show_id, id, sample_screen_id, 'AVAILABLE'
    FROM tbooker.seats WHERE screen_id = sample_screen_id
        AND seat_number IN ('A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'A7', 'A8')
    ON CONFLICT (show_id, seat_id) DO NOTHING;
END $$;
