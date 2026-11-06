#!/bin/sh
# Passes the database password from its secret file without putting it in the image or compose file.
QA_DB_PASSWORD="$(cat "$QA_DB_PASSWORD_FILE")"
export QA_DB_PASSWORD
exec java -jar /app/app.jar
