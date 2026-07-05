"""
push.py — Sends Android push notifications for M-TRACKER critical alerts.

Uses Firebase Cloud Messaging (FCM) via the firebase-admin SDK.
Requires the service account key to be present at the path given by
the FIREBASE_CREDENTIALS_PATH env var (defaults to firebase-adminsdk.json
in this directory). NEVER commit that file to a public repo — put it in
.gitignore and upload it directly to Render's environment/secret files.
"""
import os, logging

logger = logging.getLogger(__name__)

_FIREBASE_READY = False
_messaging = None

CRED_PATH = os.environ.get(
    'FIREBASE_CREDENTIALS_PATH',
    os.path.join(os.path.dirname(__file__), 'firebase-adminsdk.json')
)

def _init_firebase():
    global _FIREBASE_READY, _messaging
    if _FIREBASE_READY:
        return True
    if not os.path.exists(CRED_PATH):
        logger.warning(f'[push] Firebase credentials not found at {CRED_PATH}; push disabled.')
        return False
    try:
        import firebase_admin
        from firebase_admin import credentials, messaging
        if not firebase_admin._apps:
            cred = credentials.Certificate(CRED_PATH)
            firebase_admin.initialize_app(cred)
        _messaging = messaging
        _FIREBASE_READY = True
        logger.info('[push] Firebase initialized.')
        return True
    except Exception as e:
        logger.error(f'[push] Firebase init failed: {e}')
        return False


def send_to_tokens(tokens, title, body, critical=False, data=None):
    """
    Send a push notification to a list of FCM device tokens.
    Invalid/expired tokens are returned so callers can prune them from the DB.
    """
    if not tokens:
        return {'sent': 0, 'invalid_tokens': []}
    if not _init_firebase():
        return {'sent': 0, 'invalid_tokens': [], 'error': 'firebase_not_configured'}

    invalid = []
    sent = 0
    payload_data = {k: str(v) for k, v in (data or {}).items()}
    payload_data['critical'] = '1' if critical else '0'

    for token in tokens:
        try:
            msg = _messaging.Message(
                token=token,
                notification=_messaging.Notification(title=title, body=body),
                data=payload_data,
                android=_messaging.AndroidConfig(
                    priority='high',
                    notification=_messaging.AndroidNotification(
                        channel_id='mtracker_critical' if critical else 'mtracker_general',
                        sound='default',
                        default_vibrate_timings=critical,
                        visibility='public',
                    )
                )
            )
            _messaging.send(msg)
            sent += 1
        except Exception as e:
            msg_str = str(e).lower()
            if 'not found' in msg_str or 'unregistered' in msg_str or 'invalid' in msg_str:
                invalid.append(token)
            else:
                logger.warning(f'[push] send failed for a token: {e}')

    return {'sent': sent, 'invalid_tokens': invalid}
