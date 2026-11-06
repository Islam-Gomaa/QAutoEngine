# Browser Driver Bake-off (ADR-008)

Run 2026-11-06 on macOS arm64. Harness: `qa-exec-web/src/test/java/qaengine/web/bakeoff`.
Blocking results come from the fixture server's request log, not from driver APIs.

**Versions:** Selenium 4.52.0 with Chrome and ChromeDriver 155; Playwright 1.64.0 with its bundled Chromium.

## Must-have gates

| Gate | Selenium BiDi | Selenium CDP | Playwright |
|---|---|---|---|
| M1 Blocks before sending | **Fail:** page load stalls under interception | **Fail:** form POST, WebSocket, popup, new tab, service worker reached the server | Pass, 16 of 16 paths, after adding redirect handling |
| M2 Fails closed | Pass | Pass | Pass |
| M3 No stalls | Not measurable (stalls) | Pass, 1000 requests in 0.7s | Pass, 1000 requests in 12s |
| M4 Signals | Pass | Pass (less detail) | Pass |
| M5 Isolation | Pass | Pass | Pass |
| M6 Hardened container | Not run; failed M1 | Not run; failed M1 | Pass (see below) |
| M7 Masking | Pass | Pass | Pass |
| M8 Pinned versions | Pass | Pass, but tied to one CDP version | Pass with `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` |

## Decision

**Playwright for Java.** Under the agreed rule, a candidate must pass every must-have gate, and Playwright is the only one that did.
The scored criteria were not needed. For reference, 100 page-and-click cycles took 4.8s on Playwright and 6.0s on Selenium CDP, with no failures on either.

## What the evidence does and does not show

- **BiDi:** stalled on Selenium 4.48 and 4.52 with Chrome 155, with `Invalid InterceptionId`. The root cause was not isolated, so this may be a harness or browser-specific problem. Selenium's official examples use Firefox, which was not measured.
- **CDP:** the five misses may be partly fixable with extra target attachment and URL blocking. That would add version-coupled code, and CDP is the path Selenium is moving away from.
- **Playwright throughput:** interception is about 17 times slower than CDP on bulk requests. This does not matter for page-paced crawling but should be watched.
- **Playwright redirects:** routes do not run on redirect hops, so one path failed until the guard stopped following redirects. Redirect targets now return to the gate as new candidates.
- **Playwright downloads:** by default the Java binding downloads all browsers at startup. Images must set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`.

## M6 evidence

Container: non-root, `--read-only`, `--cap-drop=ALL --cap-add=SYS_CHROOT`, `no-new-privileges`, Playwright's seccomp profile, `--network none`, `tmpfs`, sized `shm_size`. No `--ipc=host`, no `SYS_ADMIN`, no `--no-sandbox`.

Observed in the renderer process: `Seccomp: 2`, `NoNewPrivs: 1`, `CapEff: 0`, separate PID namespace, no `--no-sandbox` flag.

**Finding:** `--cap-drop=ALL` alone crashes Chromium (`Check failed: sys_chroot`). The sandbox chroots itself, so `SYS_CHROOT` is the single capability added back. The sandbox was not disabled.

HTTPS through the egress proxy is tested in Phase 8.
