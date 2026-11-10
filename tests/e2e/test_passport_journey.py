#!/usr/bin/env python3
"""Exercise the passport journey in Chromium against a disposable Spring Boot instance.

Run directly; no pytest, running app, Mailpit, or existing database is needed.
Only the normal demo accounts are seeded. All scenario data is entered through the UI.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import signal
import socket
import struct
import subprocess
import sys
import time
import traceback
from urllib.parse import urlparse
from urllib.request import build_opener, ProxyHandler
from uuid import uuid4
import zlib

from playwright.sync_api import expect, sync_playwright

PROJECT = Path(__file__).resolve().parents[2]
PASSWORD = "DemoGuitar2026!"  # Fictitious accounts, isolated in-memory database only.
NAME = "Passport journey Stratocaster"
SERIAL = "E2E-PASSPORT-1987"
PRIVATE_NOTES = "OWNER-ONLY cost: 1234 EUR"
PRIVATE_HISTORY = "OWNER-ONLY personal history"
PRIVATE_PREVIOUS_OWNER = "OWNER-ONLY former owner's name"
PRIVATE_REQUEST = "REQUEST-ONLY appointment details"
ORIGINAL = "Neck and serial stamp consistent with the claimed model."
CORRECTED = "Replacement pickups identified; neck and serial stamp remain consistent."
REASON = "Pickguard removed during a second examination."
PRIVATE_INVOICE = "OWNER-ONLY private invoice"
CERTIFICATE = "Selected passport certificate"
LUTHIER_INVOICE = "LUTHIER-ONLY private invoice"
SHARED_EVIDENCE = "Shared examination evidence"


def sample_png() -> bytes:
    """A valid, deterministic PNG generated without image libraries or external assets."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    pixels = (b"\0" + b"\x96\x50\x26\xff" * 32) * 32
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))


def sample_pdf(label: str) -> bytes:
    """A complete one-page PDF with deterministic cross-reference offsets."""
    stream = f"BT /F1 12 Tf 40 100 Td ({label}) Tj ET".encode("ascii")
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 160] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
               b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    data = b"%PDF-1.4\n"
    offsets = []
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f"{index} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref = len(data)
    data += b"xref\n0 6\n0000000000 65535 f \n"
    data += b"".join(f"{offset:010d} 00000 n \n".encode() for offset in offsets)
    return data + f"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()


class Journey:
    def __init__(self, args):
        self.args = args
        run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
        self.artifacts = PROJECT / "target" / "e2e" / run_id
        self.artifacts.mkdir(parents=True)
        self.steps = []
        self.contexts = {}
        self.pages = {}
        self.browser_errors = []
        self.process = None
        self.server_log = None
        self.base_url = None
        self.started_at = datetime.now(timezone.utc).isoformat()

    @contextmanager
    def step(self, title):
        result = {"title": title, "status": "running"}
        self.steps.append(result)
        started = time.monotonic()
        print(f"  {title}", flush=True)
        try:
            yield
            result["status"] = "passed"
        except BaseException as error:
            result.update(status="failed", error=str(error))
            raise
        finally:
            result["seconds"] = round(time.monotonic() - started, 2)

    def start_app(self):
        with socket.socket() as port_socket:
            port_socket.bind(("127.0.0.1", 0))
            port = port_socket.getsockname()[1]
        self.base_url = f"http://127.0.0.1:{port}"
        arguments = [
            "--spring.config.location=classpath:/", "--spring.profiles.active=fixtures",
            f"--spring.datasource.url=jdbc:h2:mem:passport-e2e-{uuid4().hex};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "--spring.jpa.hibernate.ddl-auto=create-drop", "--spring.h2.console.enabled=false",
            "--server.address=127.0.0.1", f"--server.port={port}",
            "--server.servlet.session.cookie.name=GUITARVAULT_E2E_SESSION",
            f"--app.base-url={self.base_url}", "--app.admin-emails=",
            f"--app.fixtures.password={PASSWORD}", "--logging.level.root=INFO",
            "--logging.level.com.guitarvault.fixtures=INFO",
        ]
        command = [str(PROJECT / "mvnw")]
        if self.args.offline:
            command.append("-o")
        command += ["spring-boot:run", "-Dspring-boot.run.arguments=" + " ".join(arguments)]
        self.server_log = (self.artifacts / "server.log").open("w")
        # Direct argv, no shell. The complete process group is stopped even on assertion failure.
        self.process = subprocess.Popen(command, cwd=PROJECT, stdout=self.server_log,
                                        stderr=subprocess.STDOUT, start_new_session=True)
        opener = build_opener(ProxyHandler({}))
        deadline = time.monotonic() + self.args.startup_timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(f"Application exited during startup; see {self.artifacts / 'server.log'}")
            log = (self.artifacts / "server.log").read_text(errors="replace")
            if "Fixtures chargées" in log:
                with opener.open(self.base_url + "/login", timeout=3) as response:
                    if response.status == 200:
                        return
            time.sleep(0.5)
        raise TimeoutError(f"Application did not become ready; see {self.artifacts / 'server.log'}")

    def stop_app(self):
        if self.process is not None:
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(timeout=10)
        if self.server_log:
            self.server_log.close()

    def add_actor(self, browser, actor, account=None):
        context = browser.new_context(base_url=self.base_url, viewport={"width": 1365, "height": 950}, locale="en-GB")
        context.set_default_timeout(15000)
        context.tracing.start(screenshots=True, snapshots=True, sources=True)
        # The walkthrough has no reason to contact a third-party service.
        def local_only(route):
            if urlparse(route.request.url).netloc == urlparse(self.base_url).netloc:
                route.continue_()
            else:
                route.abort()
        context.route("**/*", local_only)
        self.contexts[actor] = context
        page = context.new_page()
        page.on("pageerror", lambda error: self.browser_errors.append(f"{actor}: {error}"))
        page.on("response", lambda response: self.browser_errors.append(f"{actor}: HTTP {response.status} {response.url}")
                if response.status >= 500 else None)
        self.pages[actor] = page
        if account:
            self.goto(page, "/login?lang=en")
            page.locator("#email").fill(f"{account}@guitar-vault.test")
            page.locator("#password").fill(PASSWORD)
            page.locator('form[action="/login"] button[type="submit"]').click()
            expect(page).to_have_url(self.base_url + "/dashboard")
        return page

    def goto(self, page, path):
        response = page.goto(path, wait_until="domcontentloaded")
        assert response and response.status == 200, f"Expected a page at {path}; got {response.status if response else 'no response'}"
        expect(page.locator("body")).not_to_contain_text("??")

    def screenshot(self, actor, filename):
        self.pages[actor].screenshot(path=str(self.artifacts / filename), full_page=True)
        self.steps[-1].setdefault("screenshots", []).append(filename)

    def probe(self, actor, path, expected):
        response = self.contexts[actor].request.get(path, max_redirects=0)
        assert response.status == expected, f"{actor} GET {path}: expected {expected}, got {response.status}"
        response.dispose()

    def assert_private_absent(self, page):
        for private in (PRIVATE_NOTES, PRIVATE_HISTORY, PRIVATE_PREVIOUS_OWNER, PRIVATE_REQUEST, PRIVATE_INVOICE, LUTHIER_INVOICE):
            expect(page.locator("main")).not_to_contain_text(private)

    def upload_pdf(self, page, title, filename, kind=None, visibility=None):
        page.locator("#document-title").fill(title)
        page.locator("#document-file").set_input_files({"name": filename, "mimeType": "application/pdf", "buffer": sample_pdf(title)})
        if kind:
            page.locator("#document-kind").select_option(kind)
        if visibility:
            page.locator("#document-visibility").select_option(visibility)
        page.get_by_role("button", name="Upload document", exact=True).click()
        expect(page.get_by_role("link", name=title, exact=True)).to_be_visible()
        return page.get_by_role("link", name=title, exact=True).get_attribute("href")

    def report(self, page, findings, correction=False):
        page.locator("#conclusion").select_option("CONSISTENT")
        page.locator("#findings").fill(findings)
        page.locator("#evidence").fill("Neck stamp, serial number and construction examined in person.")
        page.locator("#limitations").fill("No assessment of concealed wiring or finish chemistry.")
        if correction:
            page.locator("#correctionReason").fill(REASON)
        page.get_by_role("button", name="Save attributed report", exact=True).click()
        expect(page.locator(".assessment-revision")).to_have_count(2 if correction else 1)
        expect(page.locator(".assessment-revision").first).to_contain_text(findings)

    def scenario(self, browser):
        with self.step("Log in as two members and a luthier using separate browser sessions"):
            owner = self.add_actor(browser, "owner", "user")
            buyer = self.add_actor(browser, "buyer", "superuser")
            luthier = self.add_actor(browser, "luthier", "editor")
            self.add_actor(browser, "administrator", "admin")
            self.add_actor(browser, "anonymous")

        with self.step("Read public Markdown guides, follow guide links, download source and switch language"):
            guest = self.pages["anonymous"]
            self.goto(guest, "/login?lang=en")
            guest.locator('nav.site-nav a[href="/help"]').click()
            expect(guest.locator("h1")).to_have_text("Help & guides")
            self.screenshot("anonymous", "00-help-index.png")
            guest.get_by_role("link", name="Documents, labels and archives", exact=True).click()
            expect(guest.locator("h1")).to_have_text("Documents, labels and archives")
            guest.locator('.help-prose a[href="/help/transfers?lang=en"]').click()
            expect(guest.locator("h1")).to_have_text("Transfer an instrument to another member")
            with guest.expect_download() as source_info:
                guest.get_by_role("link", name="Download Markdown", exact=True).click()
            source_path = self.artifacts / "transfers-guide.md"
            source_info.value.save_as(str(source_path))
            assert source_path.read_bytes() == (PROJECT / "docs/user-guide/en/transfers.md").read_bytes(), "Help download differs from Git source"
            guest.locator("details.language-menu summary").click()
            guest.locator('a[hreflang="fr"]').click()
            expect(guest.locator("h1")).to_have_text("Transférer un instrument à un autre membre")
            self.goto(guest, "/help/documents?lang=en")
            guest.set_viewport_size({"width": 390, "height": 844})
            assert guest.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Help guide overflows mobile viewport"
            self.screenshot("anonymous", "00a-help-document-mobile.png")
            guest.set_viewport_size({"width": 1365, "height": 950})

        with self.step("Publish the luthier workshop through its form"):
            self.goto(luthier, "/workshop")
            for field, value in {"name": "Passport Test Workshop", "location": "Paris", "contact": "workshop@example.test",
                                 "specialties": "Vintage guitar inspections", "description": "Fictitious workshop for the passport walkthrough."}.items():
                luthier.locator(f"#{field}").fill(value)
            luthier.locator('input[name="published"]').check()
            luthier.locator('main button[type="submit"]').click()
            luthier.get_by_role("link", name="View my public page", exact=True).click()
            workshop_id = urlparse(luthier.url).path.rsplit("/", 1)[1]
            expect(luthier.locator("h1")).to_have_text("Passport Test Workshop")
            expect(luthier.locator("main")).to_contain_text("Professional identity unverified")
            self.screenshot("luthier", "01-workshop.png")

        with self.step("Luthier enables weekly availability and blocks a day off"):
            self.goto(luthier, "/workshop")
            luthier.get_by_role("link", name="Workshop availability", exact=True).click()
            luthier.locator("#availability-enabled").check()
            luthier.locator("#availability-zone").fill("Europe/Paris")
            luthier.locator("#availability-duration").select_option("30")
            luthier.locator("#availability-opens").fill("09:00")
            luthier.locator("#availability-closes").fill("17:00")
            for checkbox in luthier.locator('input[name="days"]').all():
                checkbox.check()
            luthier.get_by_role("button", name="Save availability", exact=True).click()
            blocked_day = (datetime.now(timezone.utc) + timedelta(days=9)).strftime("%Y-%m-%d")
            luthier.locator("#blocked-date").fill(blocked_day)
            luthier.get_by_role("button", name="Block this date", exact=True).click()
            expect(luthier.locator("main")).to_contain_text(blocked_day)
            self.screenshot("luthier", "01a-workshop-availability.png")

        with self.step("Musician and luthier privately arrange and confirm an appointment with a counter-proposal"):
            self.goto(owner, f"/luthiers/{workshop_id}")
            owner.get_by_role("link", name="Request an appointment", exact=True).click()
            appointment_day = (datetime.now(timezone.utc) + timedelta(days=7)).strftime("%Y-%m-%d")
            owner.locator("#slot-date").fill(appointment_day)
            owner.get_by_role("button", name="Show available times", exact=True).click()
            owner.locator("#appointment-title").fill("Private setup appointment")
            owner.locator("#appointment-slot").select_option(label="10:00–10:30")
            owner.locator("#appointment-message").fill("APPOINTMENT-ONLY private travel arrangements")
            owner.get_by_role("button", name="Send appointment request", exact=True).click()
            expect(owner).to_have_url(re.compile(r"/appointments/\d+$"))
            appointment_path = urlparse(owner.url).path
            expect(owner.get_by_role("button", name="Confirm this time", exact=True)).to_have_count(0)
            self.probe("buyer", appointment_path, 404)
            self.probe("administrator", appointment_path, 404)
            self.goto(luthier, "/notifications")
            luthier.get_by_role("button", name='An appointment conversation has an update: “Private setup appointment”.', exact=True).click()
            expect(luthier).to_have_url(self.base_url + appointment_path)
            expect(luthier.locator("main")).to_contain_text("APPOINTMENT-ONLY private travel arrangements")
            confirmed_day = (datetime.now(timezone.utc) + timedelta(days=8)).strftime("%Y-%m-%d")
            luthier.locator("#slot-date").fill(confirmed_day)
            luthier.get_by_role("button", name="Show available times", exact=True).click()
            luthier.locator("#proposal-slot").select_option(label="11:00–11:30")
            reserved_slot = luthier.locator("#proposal-slot").input_value()
            luthier.locator("#proposal-message").fill("Could you come the following day instead?")
            luthier.get_by_role("button", name="Send new time proposal", exact=True).click()
            expect(luthier.get_by_role("button", name="Confirm this time", exact=True)).to_have_count(0)
            self.goto(owner, appointment_path)
            owner.get_by_role("button", name="Confirm this time", exact=True).click()
            expect(owner.locator(".badge")).to_have_text("Confirmed")
            expect(owner.locator(".appointment-message")).to_have_count(3)
            owner.locator("#appointment-reply").fill("Thank you, see you then.")
            owner.get_by_role("button", name="Send reply", exact=True).click()
            self.goto(luthier, appointment_path + "?lang=fr")
            expect(luthier.locator(".badge")).to_have_text("Confirmé")
            expect(luthier.locator("main")).to_contain_text("Thank you, see you then.")
            luthier.set_viewport_size({"width": 390, "height": 844})
            assert luthier.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Appointment page overflows mobile viewport"
            self.screenshot("luthier", "01a-private-appointment-mobile.png")
            luthier.set_viewport_size({"width": 1365, "height": 950})
            self.goto(luthier, "/appointments?lang=en")
            expect(luthier.get_by_role("link", name="Private setup appointment", exact=True)).to_be_visible()
            self.goto(owner, appointment_path)
            self.screenshot("owner", "01b-confirmed-appointment.png")
            self.goto(buyer, f"/luthiers/{workshop_id}/appointments/new?date={confirmed_day}")
            expect(buyer.locator(f'#appointment-slot option[value="{reserved_slot}"]')).to_have_count(0)
            expect(buyer.locator("main")).not_to_contain_text("Private setup appointment")
            self.goto(buyer, f"/luthiers/{workshop_id}/appointments/new?date={blocked_day}")
            expect(buyer.get_by_role("button", name="Send appointment request", exact=True)).to_be_disabled()
            expect(buyer.locator("main")).to_contain_text("No free slots on this date")
            self.goto(luthier, f"/workshop/availability?date={confirmed_day}")
            expect(luthier.get_by_role("link", name="Private setup appointment", exact=True)).to_be_visible()
            expect(luthier.locator(".availability-slots")).not_to_contain_text("11:00–11:30")

        with self.step("Owner creates an instrument with private notes, history and a real PNG upload"):
            self.goto(owner, "/instruments")
            owner.locator('main a[href="/instruments/new"]').first.click()
            for field, value in {"name": NAME, "brand": "Fender", "year": "1987", "serialNumber": SERIAL,
                                 "notes": PRIVATE_NOTES, "history": PRIVATE_HISTORY, "previousOwner": PRIVATE_PREVIOUS_OWNER}.items():
                owner.locator(f"#{field}").fill(value)
            owner.locator("#status").select_option("En vente")
            owner.locator('input[name="recordings"]').first.fill("Passport walkthrough album")
            owner.locator('input[name="concerts"]').first.fill("Passport walkthrough concert")
            owner.locator("#photos").set_input_files({"name": "private-photo.png", "mimeType": "image/png", "buffer": sample_png()})
            owner.get_by_role("button", name="Add to my collection", exact=True).click()
            expect(owner).to_have_url(self.base_url + "/dashboard")
            self.goto(owner, "/instruments")
            owner.get_by_role("link", name=NAME, exact=True).click()
            instrument_path = urlparse(owner.url).path
            expect(owner.locator("main")).to_contain_text(SERIAL)
            expect(owner.locator("main")).to_contain_text(PRIVATE_NOTES)
            photo = owner.locator(".photo-gallery img").first
            expect(photo).to_be_visible()
            expect(photo).to_have_js_property("naturalWidth", 32)
            photo_path = photo.get_attribute("src")
            self.screenshot("owner", "02-owner-instrument.png")

        with self.step("Upload a private invoice and certificate; both are private until explicitly selected"):
            owner.get_by_role("link", name="Instrument documents", exact=True).click()
            invoice_path = self.upload_pdf(owner, PRIVATE_INVOICE, "invoice.pdf", kind="INVOICE")
            certificate_path = self.upload_pdf(owner, CERTIFICATE, "certificate.pdf", kind="CERTIFICATE")
            certificate_id = certificate_path.split("/")[-2]
            self.probe("buyer", invoice_path, 404)
            self.probe("buyer", certificate_path, 404)
            self.probe("administrator", invoice_path, 404)
            self.screenshot("owner", "02a-private-documents.png")
            self.goto(owner, instrument_path)

        with self.step("Manage personal labels, filter archived uploads, restore and delete an unused file"):
            self.goto(owner, instrument_path + "/documents")
            spare_path = self.upload_pdf(owner, "Unused test upload", "spare.pdf", kind="OTHER")
            spare_id = spare_path.split("/")[-2]
            manage_path = f"/documents/{spare_id}/manage"
            owner.locator(f'a[href="{manage_path}"]').click()
            owner.locator("#library-title").fill("My spare receipt")
            owner.locator("#edit-category").select_option("RECEIPT")
            owner.get_by_role("button", name="Save my labels", exact=True).click()
            expect(owner.locator("main")).to_contain_text("Unused test upload")
            owner.get_by_role("button", name="Archive in my library", exact=True).click()
            self.goto(owner, "/documents?q=My%20spare%20receipt")
            expect(owner.locator(f'a[href="{spare_path}"]')).to_have_count(0)
            owner.locator("#library-state").select_option("ARCHIVED")
            owner.locator("#library-category").select_option("RECEIPT")
            owner.locator("#library-instrument").select_option(instrument_path.split("/")[-1])
            owner.locator("#library-access").select_option("PRIVATE")
            owner.get_by_role("button", name="Filter documents", exact=True).click()
            expect(owner.get_by_role("link", name="My spare receipt", exact=True)).to_be_visible()
            self.screenshot("owner", "02b-archived-library-search.png")
            owner.locator(f'a[href="{manage_path}"]').click()
            owner.get_by_role("button", name="Restore to my library", exact=True).click()
            owner.get_by_role("link", name="Delete unused upload", exact=True).click()
            expect(owner.get_by_role("button", name="Permanently delete this upload", exact=True)).to_be_visible()
            self.probe("owner", spare_path, 200)
            owner.get_by_role("button", name="Permanently delete this upload", exact=True).click()
            self.probe("owner", spare_path, 404)
            self.probe("owner", invoice_path, 200)
            self.probe("owner", certificate_path, 200)
            self.goto(owner, instrument_path)

        with self.step("Owner requests an authenticity assessment; luthier receives only the selected request"):
            owner.get_by_role("link", name="Request a luthier intervention", exact=True).click()
            owner.locator("#luthierId").select_option(workshop_id)
            owner.locator("#kind").select_option("AUTHENTICITY")
            owner.locator("#description").fill(PRIVATE_REQUEST)
            owner.get_by_role("button", name="Send request", exact=True).click()
            expect(owner).to_have_url(re.compile(r"/interventions/\d+$"))
            intervention_path = urlparse(owner.url).path
            self.goto(luthier, "/interventions")
            luthier.get_by_role("link", name=NAME, exact=True).click()
            expect(luthier.locator("main")).to_contain_text(PRIVATE_REQUEST)
            expect(luthier.locator("main")).not_to_contain_text(PRIVATE_NOTES)
            self.probe("luthier", instrument_path, 404)
            self.probe("buyer", intervention_path, 404)

        with self.step("Luthier records an assessment and then a correction without replacing the first revision"):
            self.report(luthier, ORIGINAL)
            original_report_time = luthier.locator(".assessment-revision time").get_attribute("datetime")
            self.screenshot("luthier", "03-original-report.png")
            self.report(luthier, CORRECTED, correction=True)
            expect(luthier.locator(".assessment-revision").first).to_contain_text("Current report")
            expect(luthier.locator(".assessment-revision").last).to_contain_text("Previous report")
            expect(luthier.locator(".assessment-revision").last).to_contain_text(ORIGINAL)
            expect(luthier.locator(".assessment-revision").last.locator("time")).to_have_attribute("datetime", original_report_time)
            self.screenshot("luthier", "04-corrected-report.png")
            self.goto(owner, intervention_path)
            expect(owner.locator(".assessment-revision")).to_have_count(2)

        with self.step("Luthier keeps an invoice private and explicitly shares evidence with future report readers"):
            self.goto(luthier, intervention_path)
            luthier.locator(".assessment-revision").first.get_by_role("link", name="Attach supporting evidence", exact=True).click()
            expect(luthier.locator("#document-visibility")).to_have_value("PRIVATE")
            private_evidence_path = self.upload_pdf(luthier, LUTHIER_INVOICE, "private-receipt.pdf")
            luthier.locator(".assessment-revision").first.get_by_role("link", name="Attach supporting evidence", exact=True).click()
            evidence_path = self.upload_pdf(luthier, SHARED_EVIDENCE, "inspection.pdf", visibility="REPORT")
            self.goto(owner, intervention_path)
            expect(owner.locator("main")).to_contain_text(SHARED_EVIDENCE)
            expect(owner.locator("main")).not_to_contain_text(LUTHIER_INVOICE)
            self.probe("owner", private_evidence_path, 404)
            self.probe("owner", evidence_path, 200)
            self.screenshot("owner", "04a-shared-evidence.png")

        with self.step("Owner proposes a transfer; buyer opens the notification and sees only basic instrument details"):
            self.goto(owner, instrument_path)
            owner.get_by_role("link", name="Transfer my instrument", exact=True).click()
            for checkbox in owner.locator('input[name="documentIds"]').all():
                expect(checkbox).not_to_be_checked()
            owner.locator(f'input[name="documentIds"][value="{certificate_id}"]').check()
            owner.locator("#recipient").fill("demo_superuser")
            owner.locator('input[name="confirmed"]').check()
            owner.get_by_role("button", name="Send transfer proposal", exact=True).click()
            expect(owner).to_have_url(re.compile(r"/transfers/\d+$"))
            transfer_path = urlparse(owner.url).path
            self.goto(buyer, "/notifications")
            buyer.get_by_role("button", name=f"Ownership transfer proposed for “{NAME}”.", exact=True).click()
            expect(buyer).to_have_url(self.base_url + transfer_path)
            expect(buyer.locator("main")).to_contain_text(SERIAL)
            expect(buyer.locator("main")).to_contain_text("1 document(s) selected.")
            expect(buyer.locator("main")).not_to_contain_text(CERTIFICATE)
            self.probe("buyer", certificate_path, 404)
            self.probe("buyer", invoice_path, 404)
            self.assert_private_absent(buyer)
            expect(buyer.locator("main")).not_to_contain_text(ORIGINAL)
            self.probe("buyer", instrument_path, 404)
            self.probe("buyer", instrument_path + "/passport-reports", 404)
            self.probe("buyer", photo_path, 404)
            self.screenshot("buyer", "05-proposal-before-acceptance.png")

        with self.step("Buyer accepts; the same instrument and both report revisions follow the passport"):
            buyer.get_by_role("button", name="Accept and become owner", exact=True).click()
            expect(buyer.locator("main .page-header")).to_contain_text("Accepted")
            self.goto(buyer, "/instruments")
            buyer.get_by_role("link", name=NAME, exact=True).click()
            expect(buyer).to_have_url(self.base_url + instrument_path)
            expect(buyer.locator("main")).to_contain_text(SERIAL)
            expect(buyer.locator("main")).to_contain_text("Passport walkthrough album")
            expect(buyer.locator("main")).to_contain_text("Passport walkthrough concert")
            self.assert_private_absent(buyer)
            expect(buyer.locator(".photo-gallery img")).to_have_count(0)
            self.probe("buyer", photo_path, 404)
            buyer.get_by_role("link", name="Passport professional reports", exact=True).click()
            expect(buyer.locator("main article")).to_have_count(2)
            expect(buyer.locator("main")).to_contain_text(ORIGINAL)
            expect(buyer.locator("main")).to_contain_text(CORRECTED)
            expect(buyer.locator("main")).to_contain_text(REASON)
            expect(buyer.locator("main article").filter(has_text=ORIGINAL).locator("time")).to_have_text(original_report_time)
            expect(buyer.locator("main")).to_contain_text("demo_editor")
            expect(buyer.locator("main")).to_contain_text("Current report")
            expect(buyer.locator("main")).to_contain_text("Previous report")
            expect(buyer.locator("main")).not_to_contain_text("demo_user")
            self.assert_private_absent(buyer)
            expect(buyer.get_by_role("link", name=SHARED_EVIDENCE, exact=True)).to_be_visible()
            shared_download = self.contexts["buyer"].request.get(evidence_path)
            assert shared_download.status == 200 and shared_download.body() == sample_pdf(SHARED_EVIDENCE), "Shared report evidence changed"
            shared_download.dispose()
            self.probe("buyer", private_evidence_path, 404)
            self.probe("buyer", invoice_path, 404)
            self.probe("buyer", intervention_path, 404)
            self.screenshot("buyer", "06-buyer-passport-reports.png")

        self.probe("buyer", appointment_path, 404)

        with self.step("Buyer downloads only the selected certificate; unselected and luthier invoices remain private"):
            self.goto(buyer, instrument_path + "/documents")
            expect(buyer.get_by_role("link", name=CERTIFICATE, exact=True)).to_be_visible()
            self.assert_private_absent(buyer)
            with buyer.expect_download() as download_info:
                buyer.get_by_role("link", name=CERTIFICATE, exact=True).click()
            downloaded_path = self.artifacts / "selected-certificate.pdf"
            download_info.value.save_as(str(downloaded_path))
            assert downloaded_path.read_bytes() == sample_pdf(CERTIFICATE), "Transferred certificate changed"
            self.probe("buyer", invoice_path, 404)
            self.probe("buyer", private_evidence_path, 404)
            self.goto(owner, "/documents")
            expect(owner.get_by_role("link", name=PRIVATE_INVOICE, exact=True)).to_be_visible()
            self.probe("owner", invoice_path, 200)
            self.probe("administrator", certificate_path, 404)
            buyer.set_viewport_size({"width": 390, "height": 844})
            assert buyer.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Document page overflows mobile viewport"
            self.screenshot("buyer", "06a-buyer-selected-document.png")
            buyer.set_viewport_size({"width": 1365, "height": 950})

        with self.step("Buyer personal labels and archive preserve the original certificate and transfer evidence"):
            manage_path = f"/documents/{certificate_id}/manage"
            self.goto(buyer, manage_path)
            expect(buyer.get_by_role("link", name="Delete unused upload", exact=True)).to_have_count(0)
            buyer.locator("#library-title").fill("My certificate copy")
            buyer.get_by_role("button", name="Save my labels", exact=True).click()
            buyer.get_by_role("button", name="Archive in my library", exact=True).click()
            self.goto(buyer, "/documents")
            expect(buyer.locator(f'a[href="{certificate_path}"]')).to_have_count(0)
            self.probe("buyer", certificate_path, 200)
            self.goto(owner, "/documents")
            expect(owner.get_by_role("link", name=CERTIFICATE, exact=True)).to_be_visible()
            self.goto(buyer, manage_path)
            buyer.get_by_role("button", name="Restore to my library", exact=True).click()
            buyer.set_viewport_size({"width": 390, "height": 844})
            assert buyer.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Manage page overflows mobile viewport"
            self.screenshot("buyer", "06b-personal-document-labels.png")
            buyer.set_viewport_size({"width": 1365, "height": 950})

        with self.step("Former owner loses collection access but retains their private archive and original photo"):
            self.probe("owner", instrument_path, 404)
            self.probe("owner", instrument_path + "/passport-reports", 404)
            self.goto(owner, "/instruments")
            expect(owner.get_by_role("link", name=NAME, exact=True)).to_have_count(0)
            self.goto(owner, "/notifications")
            owner.get_by_role("button", name=f"The transfer proposal for “{NAME}” has been updated.", exact=True).click()
            owner.get_by_role("link", name="Ownership history", exact=True).click()
            expect(owner.locator(".professional-history > li")).to_have_count(2)
            expect(owner.locator("main")).not_to_contain_text("demo_superuser")
            owner.get_by_role("link", name="My private ownership archive", exact=True).click()
            archive_path = urlparse(owner.url).path
            for private in (PRIVATE_NOTES, PRIVATE_HISTORY, PRIVATE_PREVIOUS_OWNER):
                expect(owner.locator("main")).to_contain_text(private)
            archive_photo = owner.locator(".photo-gallery img")
            expect(archive_photo).to_have_js_property("naturalWidth", 32)
            archive_photo_path = archive_photo.get_attribute("src")
            downloaded = self.contexts["owner"].request.get(archive_photo_path)
            assert downloaded.status == 200 and downloaded.body() == sample_png(), "Archived photo changed"
            downloaded.dispose()
            self.screenshot("owner", "07-former-owner-private-archive.png")
            self.probe("owner", intervention_path, 200)
            for actor in ("buyer", "luthier", "administrator"):
                self.probe(actor, archive_path, 404)
                self.probe(actor, archive_photo_path, 404)
            self.probe("administrator", instrument_path + "/ownership", 404)
            self.probe("anonymous", archive_photo_path, 302)

        with self.step("Check the buyer's mobile view, ownership dates and French controls"):
            buyer.set_viewport_size({"width": 390, "height": 844})
            self.goto(buyer, instrument_path)
            expect(buyer.get_by_role("link", name="Transfer my instrument", exact=True)).to_be_visible()
            assert buyer.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Instrument page overflows mobile viewport"
            self.screenshot("buyer", "08-buyer-mobile.png")
            buyer.get_by_role("link", name="Ownership history", exact=True).click()
            expect(buyer.locator(".professional-history > li")).to_have_count(2)
            expect(buyer.locator(".professional-history > li").last).to_contain_text("Current")
            periods = buyer.locator(".professional-history > li")
            previous_end = periods.first.locator("time").last.inner_text()
            current_start = periods.last.locator("time").first.inner_text()
            assert previous_end == current_start, "Ownership periods have a gap or overlap"
            expect(buyer.locator("main")).not_to_contain_text("demo_user")
            self.goto(buyer, instrument_path + "?lang=fr")
            expect(buyer.get_by_role("link", name="Transférer mon instrument", exact=True)).to_be_visible()
            self.screenshot("buyer", "09-buyer-french-mobile.png")
            assert not self.browser_errors, "Browser errors: " + "; ".join(self.browser_errors)

        with self.step("Cancellation releases a reserved slot and unblocking restores a working day"):
            self.goto(owner, appointment_path + "?lang=en")
            owner.get_by_role("button", name="Cancel appointment", exact=True).click()
            expect(owner.locator(".badge")).to_have_text("Cancelled")
            self.goto(buyer, f"/luthiers/{workshop_id}/appointments/new?date={confirmed_day}&lang=en")
            expect(buyer.locator(f'#appointment-slot option[value="{reserved_slot}"]')).to_have_count(1)
            buyer.set_viewport_size({"width": 390, "height": 844})
            assert buyer.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Slot picker overflows mobile viewport"
            self.screenshot("buyer", "10-free-slot-mobile.png")
            self.goto(luthier, "/workshop/availability?lang=en")
            luthier.get_by_role("button", name="Unblock", exact=True).click()
            self.goto(buyer, f"/luthiers/{workshop_id}/appointments/new?date={blocked_day}")
            expect(buyer.locator("#appointment-slot option")).to_have_count(17)
            expect(buyer.get_by_role("button", name="Send appointment request", exact=True)).to_be_enabled()

    def save_report(self, error=None):
        result = {"status": "failed" if error else "passed", "started_at": self.started_at,
                  "base_url": self.base_url, "database": "disposable H2 in memory", "steps": self.steps,
                  "browser_errors": self.browser_errors}
        if error:
            result["error"] = error
        (self.artifacts / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        lines = ["# Passport browser walkthrough", "", f"Result: **{result['status']}**", "",
                 "Runs against a disposable in-memory database. No existing application or personal data is used.", ""]
        for step in self.steps:
            lines.append(f"- {step['status'].upper()}: {step['title']} ({step['seconds']}s)")
            lines += [f"  - [Screenshot]({name})" for name in step.get("screenshots", [])]
        lines += ["", "Browser traces: " + ", ".join(f"[{actor}]({actor}-trace.zip)" for actor in self.contexts), "",
                  "[Server log](server.log) · [Machine-readable result](result.json)"]
        if error:
            lines += ["", "```text", error, "```"]
        (self.artifacts / "report.md").write_text("\n".join(lines) + "\n")

    def run(self):
        error = None
        print(f"Artifacts: {self.artifacts}", flush=True)
        try:
            with self.step("Start an isolated application and seed the standard demo accounts"):
                self.start_app()
            with sync_playwright() as playwright:
                options = {"headless": not self.args.headed}
                if self.args.browser_executable:
                    options["executable_path"] = self.args.browser_executable
                browser = playwright.chromium.launch(**options)
                try:
                    self.scenario(browser)
                except BaseException:
                    for actor, page in self.pages.items():
                        try:
                            page.screenshot(path=str(self.artifacts / f"failure-{actor}.png"), full_page=True)
                        except Exception:
                            pass
                    raise
                finally:
                    for actor, context in self.contexts.items():
                        try:
                            context.tracing.stop(path=str(self.artifacts / f"{actor}-trace.zip"))
                        finally:
                            context.close()
                    browser.close()
        except BaseException:
            error = traceback.format_exc()
        finally:
            self.stop_app()
            self.save_report(error)
        if error:
            print(error, file=sys.stderr)
        print(f"{'FAIL' if error else 'PASS'}: {self.artifacts / 'report.md'}", flush=True)
        return 1 if error else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Use Maven's cached dependencies only")
    parser.add_argument("--headed", action="store_true", help="Show Chromium while the test runs")
    parser.add_argument("--browser-executable", help="Use an installed Chromium/Chrome executable instead of Playwright's browser")
    parser.add_argument("--startup-timeout", type=int, default=180, help="Maximum app startup time in seconds")
    return Journey(parser.parse_args()).run()


if __name__ == "__main__":
    sys.exit(main())
