"""
Gunicorn config for M-TRACKER.

IMPORTANT: /api/stream keeps a request open for up to 120s per connected
phone (long-poll). With gunicorn's default *sync* worker a single open
request would block the whole site, so we use the `gthread` worker with
plenty of threads: every connected phone holds one thread while it waits,
plus the normal browser traffic of the dashboard. Bump GUNICORN_THREADS if
you have many phones (each thread is cheap - it is mostly sleeping).

    gunicorn app:app -c gunicorn.conf.py
"""
import os

bind            = f"0.0.0.0:{os.environ.get('PORT', '5000')}"
worker_class    = 'gthread'
workers         = int(os.environ.get('WEB_CONCURRENCY', '1'))   # 1 worker => 1 scheduler
threads         = int(os.environ.get("GUNICORN_THREADS", "48"))
timeout         = 180          # > STREAM_MAX_WAIT (120s)
graceful_timeout = 30
keepalive       = 5
accesslog       = '-'
errorlog        = '-'
loglevel        = os.environ.get('LOG_LEVEL', 'info')
