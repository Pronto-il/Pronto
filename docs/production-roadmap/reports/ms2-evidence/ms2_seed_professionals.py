# -*- coding: utf-8 -*-
"""Registers the two MS2 test professionals, so the E2E can compare a near one against a far one."""
import io
import json
import subprocess

PROS = [
    # (email, phone, baseCityId, label)
    ("ms2-pro-near@example.test", "+972502345601", 43, "Near (Tel Aviv)"),
    ("ms2-pro-far@example.test", "+972502345602", 17, "Far (Haifa)"),
]

for email, phone, base_city, label in PROS:
    payload = {
        "role": "PROFESSIONAL",
        "fullName": label,
        "email": email,
        "phone": phone,
        "password": "ProntoMs2!2026",
        "professional": {
            "categoryIds": [1],
            "serviceRegionId": 4 if base_city == 43 else 2,
            "serviceCityIds": [base_city],
            "baseCityId": base_city,
            "basePrice": 250.00,
            "subServiceIds": [1, 2],
            "workingHours": [{"weekday": d, "enabled": True, "startTime": "08:00:00", "endTime": "18:00:00"} for d in range(7)],
        },
    }
    io.open("ms2-pro.json", "w", encoding="utf-8").write(json.dumps(payload, ensure_ascii=False))
    out = subprocess.run(
        ["curl", "-s", "-m", "30", "-w", "\\nHTTP %{http_code}",
         "-X", "POST", "http://localhost:8080/api/auth/register",
         "-F", "data=@ms2-pro.json;type=application/json",
         "-F", "verificationDocument=@ms2-doc.pdf;type=application/pdf"],
        capture_output=True, text=True)
    print(email, "->", out.stdout[-200:])
