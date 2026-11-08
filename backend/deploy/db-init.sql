-- Runs only on a new PostgreSQL data directory. Passwords are environment inputs, never SQL literals in Git.
\getenv app_password TEACHMI_DB_APP_PASSWORD
\getenv database_name POSTGRES_DB
CREATE ROLE teachmi_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER DATABASE :"database_name" OWNER TO teachmi_app;
REVOKE ALL ON DATABASE :"database_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO teachmi_app;
