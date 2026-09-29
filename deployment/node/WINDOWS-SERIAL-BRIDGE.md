# Windows COM bridge

The web server and native PVT engine remain inside WSL/Docker. A separate Java 21 process on Windows enumerates COM ports and transfers serial bytes over authenticated HTTP. It does not run Spring, a GUI, or a second PVT engine. USB remains owned by Windows.

## Build and run

1. Build with `gradlew.bat windowsSerialBridgeDist bootJar`. The bridge output is `build/windows-serial-bridge` (classes and four dependency jars). `linuxNodeDistZip` also includes it as `serial-bridge` plus launch scripts.
2. Install this output as `serial-bridge` next to the deployed Compose file, update `lnis.jar`, and include `deployment/node/start-serial-bridge.ps1` and `docker-compose.windows-serial.yml`.
3. Install Java 21 on Windows (`-JavaHome`, a JDK 21 under the user's `.jdks`, or Java 21 on PATH). For a sender node run `start.ps1` / `START.cmd`; the script starts the bridge before Docker Compose. A receiver node does not require the bridge.
4. Open the sender page, select COM input, refresh ports, choose COM4 or COM5, leave the default **38400 baud**, return to the test screen, then click **1에폭 수집**.
5. Disconnect u-center before capturing the same COM. After capture ends the bridge remains running but releases the COM, so u-center can use it again. The USB device does not disappear from Windows.

`docker compose up` alone does not launch a Windows Java process. The current installed PC's override loads `.serial-bridge.env`; new distributions use the `docker-compose.windows-serial.yml` overlay selected by `start.ps1`. To run Compose manually, start the bridge first, then use `docker compose -f docker-compose.yml -f docker-compose.windows-serial.yml up -d`. Add any other required overlays explicitly.

## Connection and lifecycle

`start-serial-bridge.ps1` discovers the current WSL Windows host address and binds port 18765 on that address, not all external interfaces. Mirrored networking uses loopback when the WSL route does not name a Windows host gateway. A random authentication token is reused across restarts; it is stored in local `serial-bridge/bridge.properties` and `.serial-bridge.env`. Do not commit or publish these files. WSL and the container must be able to reach the address. The current PC was tested without adding firewall rules. Other PCs may need a narrowly scoped firewall rule for the WSL interface.

The bridge starts hidden and writes `serial-bridge/bridge.log`, `bridge-error.log`, and `bridge.pid`. It is started on demand by the script, not installed as an OS service or scheduled startup task. Running the start script again reuses a healthy process. After WSL network changes, run `start.ps1` again to regenerate the address and restart as needed.

The server supports one capture session at a time. Port enumeration does not claim a port. Requests require the token; read/write/close also require the session identifier. Read/write sizes are bounded to 8192 bytes, network operations time out, and an abandoned session expires after 30 seconds. Normal completion or stop restores current-interface RAWX/SFRBX rates before closing. A crashed client or unplugged receiver may prevent restoration of temporary rates; permanent BBR/Flash configuration is never written by this transport.

Without `LNIS_SERIAL_BRIDGE_URL`, SerialCaptureService retains native local serial access, including direct USB-to-WSL setups. Do not attach a USB device to WSL when expecting the Windows bridge to enumerate it.

## Validation on this PC (2026-09-29)

- Unit HTTP tests: authentication, session ownership, byte preservation, duplicate open, unplug, expiry and unavailable server errors.
- Full `gradlew check`: passed before the final baud-default update; final targeted checks are recorded in `WINDOWS-SERIAL-VALIDATION.json`.
- Real browser: COM4 (FTDI) and COM5 (u-blox) enumerated through the WSL/Docker server.
- Real COM5 capture: continuous RAWX/SFRBX reception; current GPS L1 observations were insufficient for valid PVT. Capture timed out at 120 seconds and returned the node to READY without promoting incomplete input. The earlier valid outdoor 10-epoch fixture remains unchanged.
- No USB attach/detach is required for the bridge workflow.
Use stop.ps1 to stop Docker and the idle Windows bridge. stop-serial-bridge.ps1 refuses to terminate an active capture; finish or stop capture first.
