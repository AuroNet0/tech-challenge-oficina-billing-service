FROM python:3.13-alpine
RUN pip install --no-cache-dir psycopg[binary]==3.2.13 pymongo==4.15.3 cryptography==46.0.3
