# -*- coding: utf-8 -*-
"""Drives both MS2 test professionals through email + phone verification, then prints their JWTs."""
import json
import re
import subprocess

LOG = "C:/Users/orcoh/AppData/Local/Temp/ms2-boot.log"
DB = subprocess.run(["docker", "ps", "--filter", "publish=5433", "-q"],
                    capture_output=True, text=True).stdout.strip().splitlines()[0]


def psql(sql):
    return subprocess.run(["docker", "exec", DB, "psql", "-U", "pronto", "-d", "pronto", "-tAc", sql],
                          capture_output=True, text=True).stdout.strip()


def latest_code(email, purpose):
    body = open(LOG, encoding="utf-8", errors="replace").read()
    hits = re.findall(rf"{purpose} code for {re.escape(email)}: (\d{{6}})", body)
    return hits[-1] if hits else None


def post(path, payload):
    out = subprocess.run(["curl", "-s", "-m", "20", "-X", "POST", f"http://localhost:8080{path}",
                          "-H", "Content-Type: application/json", "--data-binary", json.dumps(payload)],
                         capture_output=True, text=True).stdout
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return {"raw": out}


for email in ("ms2-pro-near@example.test", "ms2-pro-far@example.test"):
    user_id = psql(f"select id from users where email='{email}'")
    challenge = psql(
        f"select challenge_id from verification_codes where user_id={user_id} "
        "and purpose='EMAIL_VERIFICATION' order by id desc limit 1")
    step = post("/api/auth/verify-email",
                {"challengeId": challenge, "code": latest_code(email, "EMAIL_VERIFICATION")})
    phone_challenge = (step.get("challenge") or {}).get("challengeId")
    step = post("/api/auth/verify-phone",
                {"challengeId": phone_challenge, "code": latest_code(email, "PHONE_VERIFICATION")})
    token = (step.get("session") or {}).get("token")
    professional_id = psql(f"select id from professionals where user_id={user_id}")
    # MS1: an unapproved professional cannot receive work at all, and the operator screen is out
    # of scope for this verification.
    psql(f"update professionals set approval_status='APPROVED' where id={professional_id}")
    print(json.dumps({"email": email, "userId": user_id, "professionalId": professional_id,
                      "token": token}, ensure_ascii=False))
