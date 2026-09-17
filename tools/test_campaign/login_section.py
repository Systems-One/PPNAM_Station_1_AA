"""Campaign section: Login (SCRAM, badge, gating, timeout, logout).

Prereqs: station_sim.py --headless running; C72 connected via adb; app installed
and provisioned with broker credentials.

    python tools/test_campaign/login_section.py
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from campaign import Campaign, expect
from device import Device
from simctl import SimControl

d = Device()
c = Campaign("login")


def to_login_screen(sim):
    """Fresh app process on the login screen with a reset sim world."""
    sim.cmd("reset")
    d.relaunch(wait=4)
    expect(d.find(id="etUsername") is not None, "login screen did not appear")


def scram_login(username: str, password: str):
    d.type_into("etUsername", username)
    d.type_into("etPassword", password)
    d.key("KEYCODE_BACK")
    d.tap(id="btnLogin")


def on_main() -> bool:
    return d.find(id="tileTagAssignment", retries=8) is not None


def tile_states() -> dict:
    tag = d.find(id="tileTagAssignment")
    off = d.find(id="tileOffload")
    return {"tag": tag.enabled if tag else None, "off": off.enabled if off else None}


def main():
    with SimControl() as sim:
        # ---------------------------------------------------------- L1
        with c.case("L1", "SCRAM login (op.both) reaches workflow selection, both tiles enabled") as case:
            to_login_screen(sim)
            base = len(sim.events())
            scram_login("op.both", "both123!")
            expect(on_main(), "did not reach MainActivity")
            states = tile_states()
            expect(states == {"tag": True, "off": True}, f"tile states {states}")
            proof = sim.wait_for(
                lambda e: e["dir"] == "out" and "scram_proof_result" in e["topic"], since=base)
            expect(proof and proof["payload"]["accepted"], "sim did not accept a proof")
            operator = d.find(id="tvOperator")
            expect(operator and "Bongi Both" in operator.text, f"operator label: {operator}")
            case.shot(d.screenshot("L1_main"))

        # ---------------------------------------------------------- L2
        with c.case("L2", "Wrong password shows an error and stays on login") as case:
            to_login_screen(sim)
            scram_login("op.both", "wrong-password")
            time.sleep(3)
            expect(d.find(id="etUsername", retries=3) is not None, "left the login screen")
            error = d.find(id="tvLoginError", retries=5)
            expect(error is not None and error.text.strip(), "no error message shown")
            case.note(f"error shown: {error.text!r}")
            case.shot(d.screenshot("L2_wrong_password"))

        # ---------------------------------------------------------- L3
        with c.case("L3", "Unknown username shows an error and stays on login") as case:
            to_login_screen(sim)
            scram_login("ghost", "whatever1!")
            time.sleep(3)
            expect(d.find(id="etUsername", retries=3) is not None, "left the login screen")
            error = d.find(id="tvLoginError", retries=5)
            expect(error is not None and error.text.strip(), "no error message shown")
            case.note(f"error shown: {error.text!r}")

        # ---------------------------------------------------------- L4/L5/L6
        for case_id, user, password, want in (
            ("L4", "op.tag", "tag123!", {"tag": True, "off": False}),
            ("L5", "op.off", "off123!", {"tag": False, "off": True}),
            ("L6", "op.none", "none123!", {"tag": False, "off": False}),
        ):
            with c.case(case_id, f"allowedTabs gating for {user} -> {want}") as case:
                to_login_screen(sim)
                scram_login(user, password)
                expect(on_main(), "did not reach MainActivity")
                states = tile_states()
                expect(states == want, f"tile states {states}, wanted {want}")
                case.shot(d.screenshot(f"{case_id}_{user.replace('.', '_')}"))

        # ---------------------------------------------------------- L7
        with c.case("L7", "Badge scan on login screen signs in (badge login)") as case:
            to_login_screen(sim)
            base = len(sim.events())
            d.scan_rfid("BADGE-001")
            expect(on_main(), "badge scan did not reach MainActivity")
            ctx = sim.wait_for(
                lambda e: e["dir"] == "out" and "operator_context" in e["topic"], since=base)
            expect(ctx and ctx["payload"]["accepted"], "sim did not accept the badge")
            case.shot(d.screenshot("L7_badge"))

        # ---------------------------------------------------------- L8
        with c.case("L8", "Unknown badge is rejected and stays on login") as case:
            to_login_screen(sim)
            d.scan_rfid("BADGE-NOPE")
            time.sleep(3)
            expect(d.find(id="etUsername", retries=3) is not None, "left the login screen")
            error = d.find(id="tvLoginError", retries=5)
            expect(error is not None and error.text.strip(), "no error message shown")
            case.note(f"error shown: {error.text!r}")

        # ---------------------------------------------------------- L9
        with c.case("L9", "Login timeout (station silent) surfaces an error, app stays usable") as case:
            to_login_screen(sim)
            sim.cmd("swallow-next", kind="scram_start_requested")
            scram_login("op.both", "both123!")
            time.sleep(12)  # app's request timeout is 10 s
            expect(d.find(id="etUsername", retries=3) is not None, "left the login screen")
            error = d.find(id="tvLoginError", retries=5)
            expect(error is not None and error.text.strip(), "no timeout error shown")
            case.note(f"error shown: {error.text!r}")
            case.shot(d.screenshot("L9_timeout"))
            # and the app recovers: same credentials now succeed
            scram_login("op.both", "both123!")
            expect(on_main(), "login after timeout did not succeed")

        # ---------------------------------------------------------- L10
        with c.case("L10", "Logout returns to login and closes the session at the station") as case:
            # still signed in from L9's recovery
            base = len(sim.events())
            d.tap(id="layoutOperator")
            d.tap(text="Log Out")
            time.sleep(2)
            expect(d.find(id="etUsername", retries=6) is not None, "did not return to login")
            out = sim.wait_for(
                lambda e: e["dir"] == "in" and "reader_logout_requested" in e["topic"], since=base)
            expect(out is not None, "sim never saw reader_logout_requested")
            case.shot(d.screenshot("L10_logout"))

        # ---------------------------------------------------------- L11
        with c.case("L11", "Login dropdown lists the station's operators; picking one fills the username") as case:
            to_login_screen(sim)
            asked = sim.wait_for(
                lambda e: e["dir"] == "in" and e["topic"].endswith("req/operator_list_requested"),
                timeout=15)
            expect(asked is not None, "app never requested the operator list")
            d.tap(id="etUsername")
            row = d.find(text="Thandi Tag", retries=6, partial=True)
            expect(row is not None, "dropdown did not show the simulator's operators")
            case.shot(d.screenshot("L11_dropdown"))
            d.tap(xy=row.center)
            field = d.find(id="etUsername", retries=3)
            expect(field is not None and field.text.strip() == "op.tag", f"username field {field}")
            d.type_into("etPassword", "tag123!")
            d.key("KEYCODE_BACK")
            d.tap(id="btnLogin")
            expect(on_main(), "login via dropdown pick failed")

        # ---------------------------------------------------------- L12
        with c.case("L12", "Inactivity auto sign-out returns to login with a reason") as case:
            # Set the timeout to 1 minute so this case stands alone, then restore 15.
            d.relaunch(wait=4)
            d.tap(id="btnSettings")
            d.type_into("etPin", "079545")
            d.key("KEYCODE_BACK")
            d.tap(id="btnUnlock")
            time.sleep(0.8)
            d.scroll_to("etAutoLogout")
            d.type_into("etAutoLogout", "1")
            d.key("KEYCODE_BACK")
            d.tap(id="btnSaveSettings")
            time.sleep(3)
            to_login_screen(sim)
            scram_login("op.both", "both123!")
            expect(on_main(), "login failed")
            t0 = time.time()
            # No touches from here on; uiautomator dumps are not user interaction.
            back_on_login = False
            while time.time() - t0 < 120:
                if d.find(id="etUsername", retries=1) is not None:
                    back_on_login = True
                    break
                time.sleep(5)
            expect(back_on_login, "inactivity sign-out never happened within 120s")
            err = d.find(id="tvLoginError", retries=5)
            expect(err is not None and "inactivity" in err.text.lower(), f"reason {err}")
            case.note(f"signed out after ~{int(time.time() - t0)}s: {err.text!r}")
            # restore the default
            d.tap(id="btnSettings")
            d.type_into("etPin", "079545")
            d.key("KEYCODE_BACK")
            d.tap(id="btnUnlock")
            time.sleep(0.8)
            d.scroll_to("etAutoLogout")
            d.type_into("etAutoLogout", "15")
            d.key("KEYCODE_BACK")
            d.tap(id="btnSaveSettings")
            time.sleep(3)

    return c.finish()


if __name__ == "__main__":
    sys.exit(main())
