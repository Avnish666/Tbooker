-- Legacy/sample users retain their IDs and bookings, but cannot log in until
-- a password is provisioned through a future trusted account-enrollment flow.
ALTER TABLE tbooker.users
    ADD COLUMN password_hash VARCHAR(60),
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN')),
    ADD CONSTRAINT ck_users_password_hash CHECK (
        password_hash IS NULL OR password_hash ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'
    );
