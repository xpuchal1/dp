#!/bin/bash

set -e

cat /dbprov/config/config.json

echo "Applying database migrations"
python manage.py migrate

echo "Starting server"
python manage.py runserver 0.0.0.0:8000
