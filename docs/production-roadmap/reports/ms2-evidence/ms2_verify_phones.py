# -*- coding: utf-8 -*-
"""Completes phone verification for the two MS2 test professionals.

MS1 gates marketplace eligibility on a verified phone, so an approved professional with
`phone_verified = false` correctly never appears in a listing. The SMS code is logged by
`LoggingSmsSender` against the E.164 number rather than the email, which is why this is separate
from the email step.
"""
import json
import re
import subprocess

LOG = "C:/Users/orcoh/AppData/Local/Temp/ms2-boot.log"
DB = subprocess.run(["docker", "ps", "--filter", "publish=5433", "-q"],
                    capture_output=True, text=True).stdout.strip().splitlines()[0]

PROS = [("ms2-pro-near@example.test", "+972502345601"), ("ms2-pro-far@example.test", "+972502345602")]


def psql(sql):
    return subprocess.run(["docker", "exec", DB, "psql", "-U", "pronto", "-d", "pronto", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8", errors="replace").stdout.strip()


def sms_code(phone):
    body = open(LOG, encoding="utf-8", errors="replace").read()
    hits = re.findall(rf"PHONE_VERIFICATION code for {re.escape(phone)}: (\d{{6}})", body)
    return hits[-1] if hits else None


def post(path, payload):
    out = subprocess.run(["curl", "-s", "-m", "20", "-X", f"POST", f"http://localhost:8080{path}",
                          "-H", "Content-Type: application/json", "--data-binary", json.dumps(payload)],
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return {"raw": out}


for email, phone in PROS:
    user_id = psql(f"select id from users where email='{email}'")
    challenge = psql(
        f"select challenge_id from verification_codes where user_id={user_id} "
        "and purpose='PHONE_VERIFICATION' and consumed_at is null order by id desc limit 1")
    result = post("/api/auth/verify-phone", {"challengeId": challenge, "code": sms_code(phone)})
    verified = psql(f"select phone_verified from users where id={user_id}")
    print(f"{email}: phone_verified={verified}  nextStep={result.get('nextStep')}")
