# -*- coding: utf-8 -*-
"""
Production MS2 -- local end-to-end verification, driven entirely through the real HTTP API.

Runs against the FAKE maps provider (`pronto.maps.mode=fake`), which is the only provider
available without a Google Maps Platform credential. That distinction matters and is recorded
honestly in the MS2 report: this proves every MS2 decision boundary, every persistence path and
every degraded state end to end, and it does NOT prove that Google resolves Israeli Hebrew
addresses well. The second is a separate, still-outstanding step.

The fake provider is not a stub in the way the pre-MS2 placeholder was: it anchors addresses at
real city coordinates and derives distance and duration from real geometry, so "Haifa is much
further from Tel Aviv than Ramat Gan is" is a fact these flows actually exercise.
"""
import json
import os
import subprocess
import sys

API = "http://localhost:8080"
DB = subprocess.run(["docker", "ps", "--filter", "publish=5433", "-q"],
                    capture_output=True, text=True, encoding="utf-8", errors="replace").stdout.strip().splitlines()[0]

# Overridable so the same flows can be re-run against a different provider mode
# with a fresh customer (a customer's address is geocoded once, at registration).
CUSTOMER = os.environ.get("MS2_CUSTOMER", "ms2-cust2@example.test")
PRO_NEAR = "ms2-pro-near@example.test"
PRO_FAR = "ms2-pro-far@example.test"
PASSWORD = "ProntoMs2!2026"

# Dizengoff 10, Tel Aviv, as the fake geocoder resolves it -- filled in at run time.
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
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    raw, _, status = out.rpartition("\n")
    try:
        return int(status), json.loads(raw) if raw.strip() else None
    except json.JSONDecodeError:
        return int(status), {"raw": raw}


LOG = os.environ.get("MS2_LOG", "C:/Users/orcoh/AppData/Local/Temp/ms2-boot2.log")


def latest_code(email, purpose):
    import re
    body = open(LOG, encoding="utf-8", errors="replace").read()
    hits = re.findall(rf"{purpose} code for {re.escape(email)}: (\d{{6}})", body)
    return hits[-1] if hits else None


def login(email):
    """MS1 made login a two-step OTP flow; the code is read from the local dev log."""
    status, body = call("POST", "/api/auth/login", body={"identifier": email, "password": PASSWORD})
    assert status == 200, (email, status, body)
    if body.get("nextStep") == "LOGIN_OTP":
        challenge = body["challenge"]["challengeId"]
        status, body = call("POST", "/api/auth/login/otp",
                            body={"challengeId": challenge, "code": latest_code(email, "EMAIL_LOGIN_OTP")})
        assert status == 200, (email, status, body)
    return body["session"]["token"]


def check(name, condition, detail=""):
    (passed if condition else failed).append(name)
    print(f"{'PASS' if condition else 'FAIL'}  {name}{('  -- ' + str(detail)) if detail else ''}")


def send_location(token, lat, lon, accuracy, captured_at=None):
    from datetime import datetime, timezone
    body = {
        "latitude": lat,
        "longitude": lon,
        "accuracyMeters": accuracy,
        "capturedAt": captured_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    return call("PUT", "/api/professionals/me/location", token, body)


def main():
    customer = login(CUSTOMER)
    near = login(PRO_NEAR)
    far = login(PRO_FAR)
    near_id = int(psql(f"select id from professionals where user_id=(select id from users where email='{PRO_NEAR}')"))
    far_id = int(psql(f"select id from professionals where user_id=(select id from users where email='{PRO_FAR}')"))

    print("\n########## 1. Professional current location ##########")
    # Near: ~1.5 km from Dizengoff 10. Far: central Haifa, ~85 km away.
    status, body = send_location(near, 32.0900, 34.7800, 12.0)
    check("PUT /me/location accepts a good fix and reports it usable",
          status == 200 and body["usable"] is True, body)
    check("the location response carries no coordinates (privacy)",
          "latitude" not in json.dumps(body) and "longitude" not in json.dumps(body), body)

    status, body = send_location(far, 32.7940, 34.9896, 15.0)
    check("second professional's fix accepted", status == 200 and body["usable"] is True, body)

    status, body = send_location(near, 32.0900, 34.7800, 200000.0)
    check("an implausibly large accuracy is a 400, not a stored value", status == 400, body)

    # A large-but-plausible figure IS stored -- and is then correctly judged unusable, which is
    # the distinction the design draws: a poor fix is data, a nonsensical one is a malformed
    # client.
    status, body = send_location(near, 32.0900, 34.7800, 5000.0)
    check("a poor-but-plausible fix is stored and reported UNUSABLE, not rejected",
          status == 200 and body["usable"] is False
          and body["reason"] == "PROFESSIONAL_LOCATION_INACCURATE", body)
    send_location(near, 32.0900, 34.7800, 12.0)

    status, body = send_location(near, 200.0, 34.78, 10.0)
    check("an out-of-range coordinate is a 400", status == 400, body)

    stored = psql(f"select count(*) from professional_locations where professional_id={near_id}")
    check("exactly one current row per professional (replace, not append)", stored == "1", stored)

    print("\n########## 2. Geocoding + real listing ##########")
    status, issue = call("POST", "/api/issues", customer,
                         {"categoryId": 1, "description": "יש נזילה מתחת לכיור במטבח, המים ממשיכים לזרום",
                          "urgencyType": "STANDARD"})
    if status != 201:
        print("issue creation failed:", status, issue)
        sys.exit(1)
    issue_id = issue["id"]

    status, listing = call(
        "GET",
        f"/api/bookings/professionals?issueId={issue_id}&city=%D7%AA%D7%9C%20%D7%90%D7%91%D7%99%D7%91"
        "&street=%D7%93%D7%99%D7%96%D7%A0%D7%92%D7%95%D7%A3&houseNumber=10&sort=FASTEST",
        customer)
    check("GET /api/bookings/professionals returns 200", status == 200, status)
    cards = {c["professionalId"]: c for c in (listing or {}).get("professionals", [])}
    near_card, far_card = cards.get(near_id), cards.get(far_id)

    check("the customer's default address was geocoded and persisted",
          psql(f"select default_geocode_status from users where email='{CUSTOMER}'") == "RESOLVED")
    check("the geocoded city was reconciled to the service_cities catalogue",
          psql(f"select default_service_city_id from users where email='{CUSTOMER}'") != "")

    if near_card and far_card:
        check("both professionals get a real distance",
              near_card["distanceKm"] is not None and far_card["distanceKm"] is not None,
              (near_card["distanceKm"], far_card["distanceKm"]))
        check("distances are NOT the 8.0/35.0 km placeholders",
              near_card["distanceKm"] not in (8.0, 35.0) and far_card["distanceKm"] not in (8.0, 35.0),
              (near_card["distanceKm"], far_card["distanceKm"]))
        check("ETAs are NOT the 34/40/54/70 minute placeholders",
              near_card["etaMinutes"] not in (34, 40, 54, 70)
              and far_card["etaMinutes"] not in (34, 40, 54, 70),
              (near_card["etaMinutes"], far_card["etaMinutes"]))
        check("the Haifa professional is measurably further than the Tel Aviv one",
              far_card["distanceKm"] > near_card["distanceKm"] * 5,
              (near_card["distanceKm"], far_card["distanceKm"]))
        check("FASTEST orders by real duration (nearest first)",
              [c["professionalId"] for c in listing["professionals"]][:1] == [near_id],
              [(c["professionalId"], c["etaMinutes"]) for c in listing["professionals"]])
        check("no card exposes raw coordinates or accuracy",
              not any(k in json.dumps(listing) for k in ("latitude", "longitude", "accuracyMeters")))
    else:
        check("both professionals appear in the listing", False, list(cards))

    print("\n########## 3. Stale location degrades truthfully ##########")
    psql(f"update professional_locations set captured_at = now() - interval '2 hours', "
         f"updated_at = now() - interval '2 hours' where professional_id={far_id}")
    status, listing2 = call(
        "GET",
        f"/api/bookings/professionals?issueId={issue_id}&city=%D7%AA%D7%9C%20%D7%90%D7%91%D7%99%D7%91"
        "&street=%D7%93%D7%99%D7%96%D7%A0%D7%92%D7%95%D7%A3&houseNumber=10&sort=FASTEST",
        customer)
    cards2 = {c["professionalId"]: c for c in listing2["professionals"]}
    stale_card = cards2.get(far_id)
    if stale_card:
        check("a stale-location professional still appears in the normal listing",
              True)
        check("...but with NO distance and NO ETA",
              stale_card["distanceKm"] is None and stale_card["etaMinutes"] is None, stale_card)
        check("...and a machine-readable reason",
              stale_card["etaUnavailableReason"] == "PROFESSIONAL_LOCATION_STALE",
              stale_card["etaUnavailableReason"])
        check("...and sorts last under FASTEST",
              [c["professionalId"] for c in listing2["professionals"]][-1] == far_id,
              [(c["professionalId"], c["etaMinutes"]) for c in listing2["professionals"]])
    else:
        check("stale professional still listed", False)

    print("\n########## 4. Order destination snapshot + committed ETA ##########")
    status, windows = call("GET", f"/api/bookings/professionals/{near_id}/available-windows?issueId={issue_id}",
                           customer)
    slots = (windows or {}).get("windows", [])
    if not slots:
        check("an available booking window exists", False, windows)
        return summary()
    status, order = call("POST", "/api/bookings/orders", customer, {
        "issueId": issue_id, "professionalId": near_id, "bookedStart": slots[0]["startAt"],
        "serviceCity": "תל אביב", "serviceStreet": "דיזנגוף", "serviceHouseNumber": "10",
    })
    check("order created", status == 201, (status, order))
    order_id = order["id"]
    snap = psql(f"select service_latitude, service_longitude from orders where id={order_id}")
    check("the order snapshotted its destination coordinates", snap.count("|") == 1 and snap.split("|")[0] != "", snap)

    # The customer moves house. The order must not move with them.
    psql(f"update users set default_city='חיפה', default_latitude=32.794, default_longitude=34.9896, "
         f"default_geocode_status='RESOLVED' where email='{CUSTOMER}'")
    snap_after = psql(f"select service_latitude, service_longitude from orders where id={order_id}")
    check("editing the customer's default address does not move an existing order",
          snap_after == snap, (snap, snap_after))

    call("POST", f"/api/bookings/orders/{order_id}/accept", near)
    status, otw = call("POST", f"/api/bookings/orders/{order_id}/on-the-way", near)
    check("ON_THE_WAY transition succeeds", status == 200, status)
    check("expectedArrivalAt was committed from a real routed ETA",
          otw and otw.get("expectedArrivalAt") is not None, otw and otw.get("expectedArrivalAt"))

    print("\n########## 5. Verified arrival ##########")
    from datetime import datetime, timezone
    now = lambda: datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    dest_lat, dest_lon = [float(v) for v in snap.split("|")]

    status, body = call("POST", f"/api/bookings/orders/{order_id}/arrived", near, {
        "latitude": dest_lat + 0.05, "longitude": dest_lon, "accuracyMeters": 10.0, "capturedAt": now()})
    check("pressing הגעתי ~5 km away is REFUSED",
          status == 422 and body["error"]["code"] == "ARRIVAL_OUT_OF_RANGE", (status, body))
    check("the refusal does not disclose the distance or the destination",
          "32.0" not in json.dumps(body.get("error", {}).get("message", "")), body)
    check("the order did not move", psql(f"select order_status from orders where id={order_id}") == "ON_THE_WAY")

    status, body = call("POST", f"/api/bookings/orders/{order_id}/arrived", near, {
        "latitude": dest_lat, "longitude": dest_lon, "accuracyMeters": 500.0, "capturedAt": now()})
    check("an imprecise fix at the right place is REFUSED",
          status == 422 and body["error"]["code"] == "LOCATION_QUALITY_INSUFFICIENT", (status, body))

    from datetime import timedelta
    stale_at = (datetime.now(timezone.utc) - timedelta(minutes=10)).isoformat().replace("+00:00", "Z")
    status, body = call("POST", f"/api/bookings/orders/{order_id}/arrived", near, {
        "latitude": dest_lat, "longitude": dest_lon, "accuracyMeters": 10.0, "capturedAt": stale_at})
    check("a stale fix at the right place is REFUSED",
          status == 422 and body["error"]["code"] == "LOCATION_QUALITY_INSUFFICIENT", (status, body))

    # ~50 m north of the destination: inside the 150 m geofence.
    status, body = call("POST", f"/api/bookings/orders/{order_id}/arrived", near, {
        "latitude": dest_lat + 0.00045, "longitude": dest_lon, "accuracyMeters": 10.0, "capturedAt": now()})
    check("a fresh, precise fix at the door is ACCEPTED",
          status == 200 and body["orderStatus"] == "ARRIVED", (status, body))
    evidence = psql(f"select arrived_at is not null, arrival_distance_meters from orders where id={order_id}")
    check("the arrival evidence was recorded", evidence.startswith("t|"), evidence)
    check("the customer-facing order response carries no arrival coordinates",
          not any(k in json.dumps(body) for k in ("arrivalLatitude", "arrivalLongitude", "arrivalAccuracy")))

    status, body = call("POST", f"/api/bookings/orders/{order_id}/complete", near)
    check("completion still works from ARRIVED", status == 200 and body["orderStatus"] == "COMPLETED", status)

    summary()


def summary():
    print(f"\n########## {len(passed)} passed, {len(failed)} failed ##########")
    for name in failed:
        print("  FAILED:", name)
    sys.exit(1 if failed else 0)


main()
