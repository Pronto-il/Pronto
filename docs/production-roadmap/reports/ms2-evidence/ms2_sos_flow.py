# -*- coding: utf-8 -*-
"""
Production MS2 -- the SOS half of the local end-to-end verification.

Proves the stricter SOS rule that has no equivalent in the Standard flow: a professional without
a sufficiently fresh, usable current position does not participate in geographic SOS matching at
all -- not approximated from their base city, not ranked with a neutral score, not dispatched.

Same caveat as `ms2_flow.py`: this runs against the FAKE maps provider, so it proves the
decision boundaries and not the quality of Google's Israeli geocoding.
"""
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

API = "http://localhost:8080"
LOG = os.environ.get("MS2_LOG", "C:/Users/orcoh/AppData/Local/Temp/ms2-boot2.log")
PASSWORD = "ProntoMs2!2026"
# Overridable so the same flows can be re-run against a different provider mode
# with a fresh customer (a customer's address is geocoded once, at registration).
CUSTOMER = os.environ.get("MS2_CUSTOMER", "ms2-cust2@example.test")
PRO_NEAR = "ms2-pro-near@example.test"
PRO_FAR = "ms2-pro-far@example.test"

DB = subprocess.run(["docker", "ps", "--filter", "publish=5433", "-q"],
                    capture_output=True, text=True).stdout.strip().splitlines()[0]
passed, failed = [], []


def psql(sql):
    return subprocess.run(["docker", "exec", DB, "psql", "-U", "pronto", "-d", "pronto", "-tAc", sql],
                          capture_output=True, text=True, encoding="utf-8", errors="replace").stdout.strip()


def call(method, path, token=None, body=None):
    cmd = ["curl", "-s", "-m", "30", "-X", method, f"{API}{path}", "-w", "\n%{http_code}"]
    if token:
        cmd += ["-H", f"Authorization: Bearer {token}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "--data-binary", json.dumps(body)]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout or ""
    raw, _, status = out.rpartition("\n")
    try:
        return int(status), (json.loads(raw) if raw.strip() else None)
    except (ValueError, json.JSONDecodeError):
        return (int(status) if status.strip().isdigit() else 0), {"raw": raw}


def latest_code(pattern):
    body = open(LOG, encoding="utf-8", errors="replace").read()
    hits = re.findall(pattern, body)
    return hits[-1] if hits else None


def login(email):
    status, body = call("POST", "/api/auth/login", body={"identifier": email, "password": PASSWORD})
    assert status == 200, (email, status, body)
    if body.get("nextStep") == "LOGIN_OTP":
        status, body = call("POST", "/api/auth/login/otp", body={
            "challengeId": body["challenge"]["challengeId"],
            "code": latest_code(rf"EMAIL_LOGIN_OTP code for {re.escape(email)}: (\d{{6}})")})
        assert status == 200, (email, status, body)
    return body["session"]["token"]


def check(name, condition, detail=""):
    (passed if condition else failed).append(name)
    print(f"{'PASS' if condition else 'FAIL'}  {name}{('  -- ' + str(detail)) if detail else ''}")


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def send_location(token, lat, lon, accuracy):
    return call("PUT", "/api/professionals/me/location", token,
                {"latitude": lat, "longitude": lon, "accuracyMeters": accuracy, "capturedAt": now()})


def main():
    customer = login(CUSTOMER)
    near = login(PRO_NEAR)
    far = login(PRO_FAR)
    near_id = int(psql(f"select id from professionals where user_id=(select id from users where email='{PRO_NEAR}')"))
    far_id = int(psql(f"select id from professionals where user_id=(select id from users where email='{PRO_FAR}')"))

    # Both professionals must be SOS-available to be eligible at all.
    for pid in (near_id, far_id):
        psql(f"insert into sos_availability (professional_id, is_available) values ({pid}, true) "
             f"on conflict (professional_id) do update set is_available = true")

    print("\n########## SOS 1. Both professionals have a fresh position ##########")
    send_location(near, 32.0900, 34.7800, 12.0)     # ~1.5 km from the destination
    send_location(far, 32.7940, 34.9896, 15.0)      # Haifa, ~85 km away
    check("both professionals report a usable position",
          psql(f"select count(*) from professional_locations where professional_id in ({near_id},{far_id})") == "2")

    status, issue = call("POST", "/api/issues", customer,
                         {"categoryId": 1, "description": "צינור התפוצץ במטבח והמים מציפים את הדירה",
                          "urgencyType": "SOS"})
    if status != 201:
        print("issue creation failed:", status, issue)
        sys.exit(1)
    issue_id = issue["id"]

    status, request = call("POST", "/api/sos/requests", customer, {
        "issueId": issue_id, "urgency": "URGENT",
        "serviceCity": "תל אביב", "serviceStreet": "דיזנגוף", "serviceHouseNumber": "10"})
    check("SOS request created", status == 201, (status, request))
    if status != 201:
        return summary()
    sos_id = request["id"]

    dest = psql(f"select latitude, longitude, geocode_status from sos_requests where id={sos_id}")
    check("the SOS destination was geocoded at creation", dest.endswith("|RESOLVED"), dest)

    offers = psql(f"select professional_id, distance_km, estimated_arrival_minutes from sos_offers "
                  f"where sos_request_id={sos_id} order by match_rank")
    print("  offers:", offers.replace("\n", " ; "))
    offered_ids = [line.split("|")[0] for line in offers.splitlines() if line]

    check("the nearby professional was dispatched an offer", str(near_id) in offered_ids, offered_ids)
    near_row = [l for l in offers.splitlines() if l.startswith(f"{near_id}|")]
    if near_row:
        distance = float(near_row[0].split("|")[1])
        eta = int(near_row[0].split("|")[2])
        check("the offer carries a REAL distance, not 8.0/35.0", distance not in (8.0, 35.0), distance)
        check("the offer carries a REAL ETA, not 34/40/54/70", eta not in (34, 40, 54, 70), eta)

    check("the 85 km professional is OUTSIDE the 40 km dispatch radius and was not offered",
          str(far_id) not in offered_ids, offered_ids)

    print("\n########## SOS 2. A stale-location professional is excluded entirely ##########")
    # Cancel and retry with the nearby professional's position aged out.
    call("POST", f"/api/sos/requests/{sos_id}/cancel", customer)
    psql(f"update professional_locations set captured_at = now() - interval '2 hours', "
         f"updated_at = now() - interval '2 hours' where professional_id={near_id}")

    status, issue2 = call("POST", "/api/issues", customer,
                          {"categoryId": 1, "description": "שוב נזילה חמורה מתחת לכיור, צריך מישהו דחוף",
                           "urgencyType": "SOS"})
    status, request2 = call("POST", "/api/sos/requests", customer, {
        "issueId": issue2["id"], "urgency": "URGENT",
        "serviceCity": "תל אביב", "serviceStreet": "דיזנגוף", "serviceHouseNumber": "10"})
    if status != 201:
        check("second SOS request created", False, (status, request2))
        return summary()
    sos_id2 = request2["id"]

    offers2 = psql(f"select professional_id from sos_offers where sos_request_id={sos_id2}")
    check("a professional whose position is stale receives NO SOS offer",
          str(near_id) not in offers2.splitlines(), offers2)
    check("the request failed honestly rather than dispatching to a stale-location professional",
          psql(f"select status from sos_requests where id={sos_id2}") == "FAILED",
          psql(f"select status from sos_requests where id={sos_id2}"))
    check("...and no 8/35 km fallback distance was recorded anywhere",
          psql(f"select count(*) from sos_offers where sos_request_id={sos_id2} "
               f"and distance_km in (8.0, 35.0)") == "0")

    print("\n########## SOS 3. Real geography restores eligibility ##########")
    send_location(near, 32.0900, 34.7800, 12.0)
    status, issue3 = call("POST", "/api/issues", customer,
                          {"categoryId": 1, "description": "הדוד מטפטף חזק והמים מגיעים לרצפה",
                           "urgencyType": "SOS"})
    status, request3 = call("POST", "/api/sos/requests", customer, {
        "issueId": issue3["id"], "urgency": "URGENT",
        "serviceCity": "תל אביב", "serviceStreet": "דיזנגוף", "serviceHouseNumber": "10"})
    if status == 201:
        offers3 = psql(f"select professional_id, distance_km from sos_offers where sos_request_id={request3['id']}")
        check("the same professional, with a fresh position, IS dispatched again",
              str(near_id) in [l.split("|")[0] for l in offers3.splitlines() if l], offers3)
        call("POST", f"/api/sos/requests/{request3['id']}/cancel", customer)
    else:
        check("third SOS request created", False, (status, request3))

    summary()


def summary():
    print(f"\n########## {len(passed)} passed, {len(failed)} failed ##########")
    for name in failed:
        print("  FAILED:", name)
    sys.exit(1 if failed else 0)


main()
