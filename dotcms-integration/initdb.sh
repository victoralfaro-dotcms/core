export PGUSER=postgres
psql <<- EOSQL
CREATE ROLE postgres1 SUPERUSER LOGIN PASSWORD 'postgres';
CREATE DATABASE dotcms1;
GRANT ALL PRIVILEGES ON DATABASE dotcms1 TO postgres1;

CREATE ROLE postgres2 SUPERUSER LOGIN PASSWORD 'postgres';
CREATE DATABASE dotcms2;
GRANT ALL PRIVILEGES ON DATABASE dotcms2 TO postgres2;

CREATE ROLE postgres3 SUPERUSER LOGIN PASSWORD 'postgres';
CREATE DATABASE dotcms3;
GRANT ALL PRIVILEGES ON DATABASE dotcms3 TO postgres3;

CREATE ROLE postgres4 SUPERUSER LOGIN PASSWORD 'postgres';
CREATE DATABASE dotcms4;
GRANT ALL PRIVILEGES ON DATABASE dotcms4 TO postgres4;

EOSQL
