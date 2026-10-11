"""
Gunicorn config for M-TRACKER.

IMPORTANT: /api/stream keeps a request open for up to 120s per connected
phone (long-poll). With gunicorn's default *sync* worker a single open
request would block the whole site, so we use the `gthread` worker with
plenty of threads: a handful of phones + normal browser traffic all fit.

    gunicorn app:app -c gunicorn.conf.py
"""
import os

bind            = f"0.0.0.0:{os.environ.get('PORT', '5000')}"
worker_class    = 'gthread'
workers         = int(os.environ.get('WEB_CONCURRENCY', '1'))   # 1 worker => 1 scheduler
threads         = int(os.environ.get('GUNICORN_THREADS', '24'))
timeout         = 180          # > STREAM_MAX_WAIT (120s)
graceful_timeout = 30
keepalive       = 5
accesslog       = '-'
errorlog        = '-'
loglevel        = os.environ.get('LOG_LEVEL', 'info')
