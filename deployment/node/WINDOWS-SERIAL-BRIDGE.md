# Windows COM bridge

The web server and native PVT engine remain inside WSL/Docker. A separate Java 21 process on Windows enumerates COM ports and transfers serial bytes over authenticated HTTP. It does not run Spring, a GUI, or a second PVT engine. USB remains owned by Windows.

## Build and run

1. Build with `gradlew.bat windowsSerialBridgeDist bootJar`. The bridge output is `build/windows-serial-bridge` (classes and four dependency jars). `linuxNodeDistZip` also includes it as `serial-bridge` plus launch scripts.
2. For an existing installation, `scripts/deploy-nodes.ps1` installs the matching JAR and bridge together while retaining the running Compose overlays. Active capture or trial preparation/sending/calculation blocks deployment. An idle persistent connection is released by graceful service shutdown before replacing the bridge; reconnect GNSS after deployment. `-ValidateOnly` checks the deployment without restarting it. For a new installation, install this output as `serial-bridge` next to the deployed Compose file, update `lnis.jar`, and include `deployment/node/start-serial-bridge.ps1` and `docker-compose.windows-serial.yml`.
3. Install Java 21 on Windows (`-JavaHome`, a JDK 21 under the user's `.jdks`, or Java 21 on PATH). Both roles use `start.ps1` / `START.cmd`; bridge startup failure leaves file/REST operation available.
4. In sender settings or the receiver connection strip, select a COM port and **38400 baud**, then click **연결**. The sender can now run **1 Epoch 수집** from its test screen.
5. Disconnect u-center before opening the same COM. Capture completion and browser closure keep the COM connected. Click **연결 해제** to release it. Service restart requires manual connection; only an unambiguously identified device is retried automatically after temporary unplug.

`docker compose up` alone does not launch a Windows Java process. The current installed PC's override loads `.serial-bridge.env`; new distributions use the `docker-compose.windows-serial.yml` overlay selected by `start.ps1`. To run Compose manually, start the bridge first, then use `docker compose -f docker-compose.yml -f docker-compose.windows-serial.yml up -d`. Add any other required overlays explicitly.

## Connection and lifecycle

Docker Desktop development only: run `start-serial-bridge.ps1 -DockerDesktop` to bind Windows loopback and configure `host.docker.internal`. Keep the local Compose overlay loading `.serial-bridge.env`; do not commit machine-specific settings. The standard `start.ps1` remains for WSL2 Docker Engine deployment.

`start-serial-bridge.ps1` discovers the current WSL Windows host address and binds port 18765 on that address, not all external interfaces. Mirrored networking uses loopback when the WSL route does not name a Windows host gateway. A random authentication token is reused across restarts; it is stored in local `serial-bridge/bridge.properties` and `.serial-bridge.env`. Do not commit or publish these files. WSL and the container must be able to reach the address. The current PC was tested without adding firewall rules. Other PCs may need a narrowly scoped firewall rule for the WSL interface.

The bridge starts hidden and writes `serial-bridge/bridge.log`, `bridge-error.log`, and `bridge.pid`. It is started on demand by the script, not installed as an OS service or scheduled startup task. Running the start script again reuses a healthy process only when its `/health.version` matches the SHA-256 of the installed classes and libraries. The expected version is recorded in `serial-bridge/bridge-version.txt`. An outdated idle process is restarted; an active GNSS connection blocks replacement. A stale PID belonging to another process is discarded without stopping that process. After WSL network changes, run `start.ps1` again to regenerate the address and restart as needed.

The server supports one persistent connection and one capture at a time. Enumeration does not claim a port. Requests require the token; read/write/close also require the session identifier. Read/write sizes are bounded to 8192 bytes; an abandoned session expires after 30 seconds. Sender connection temporarily configures RAWX/SFRBX and restores rates on normal disconnect, not capture completion. Unplug/crash may prevent restoration; BBR/Flash is not changed. NAV-TIMEUTC polling monitors time only: it never sets the PC clock. Missing GNSS on either or both sides does not block file/REST tests. Receiver bridge uses port 18766 to avoid collisions with sender 18765 on a development PC.

Without `LNIS_SERIAL_BRIDGE_URL`, SerialCaptureService retains native local serial access, including direct USB-to-WSL setups. Do not attach a USB device to WSL when expecting the Windows bridge to enumerate it.

## Validation on this PC (2026-09-29)

### Internal trial clock

The authenticated read-only `/time` bridge endpoint estimates UTC at the serial read boundary from recent valid NAV-TIMEUTC messages. Update both the bridge distribution and server JAR. **No OS clock setter, Windows Time service change, administrator privilege, or container `SYS_TIME` capability is used.**

The web **시간 맞추기** button previews and applies an offset to the service's trial clock only. The calibrated clock advances using a monotonic timer; existing system logs keep PC time. Missing local GNSS falls back to a recently GNSS-calibrated peer, then the common `LNIS_NTP_SERVER` (default `time.windows.com`). Failure retains the current clock. Serial message output latency and network asymmetry remain; sample consistency is not proof of absolute accuracy. Restart resets calibration. See the project README for trial evidence and reservation rules.

- Unit HTTP tests: authentication, session ownership, byte preservation, duplicate open, unplug, expiry and unavailable server errors.
- Full `gradlew check`: passed before the final baud-default update; final targeted checks are recorded in `WINDOWS-SERIAL-VALIDATION.json`.
- Real browser: COM4 (FTDI) and COM5 (u-blox) enumerated through the WSL/Docker server.
- Real COM5 capture: continuous RAWX/SFRBX reception; current GPS L1 observations were insufficient for valid PVT. Capture timed out at 120 seconds and returned the node to READY without promoting incomplete input. The earlier valid outdoor 10-epoch fixture remains unchanged.
- No USB attach/detach is required for the bridge workflow.
Use stop.ps1 to stop Docker and the idle Windows bridge. stop-serial-bridge.ps1 refuses to terminate an active GNSS connection; disconnect it first.
