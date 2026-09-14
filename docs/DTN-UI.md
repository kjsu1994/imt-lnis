# DTN UI and adapter boundary

## Current behavior

- AFS test pages and TEST_A through TEST_D are unchanged.
- GRAW upload uses the existing input API. `GET /lnis/api/v1/dtn/inputs/{id}/observations`
  is a read-only projection of a completed input of at most 1 MiB. It does not transmit data.
- The DTN report adds `observations` (epochs, navigationCount, receiver metadata) and the
  summary adds `referenceEpochs`. Existing fields and external AFS JSON are unchanged.
- Restored observations are persisted locally in the nullable `dtn_job.observations_json`
  column. Older trials display an empty observation table if this field is absent.
- Sender PVT is computed by the existing PREPARE operation when the trial starts.
  Receiver PVT is calculated independently after AFS decoding. UI never substitutes
  receiver NAV-PVT, PC time, DTN arrival time, or hard-coded coordinates for these results.
- Both use terrestrial GPS L1 C/A SPP with ECEF position and velocity. All recorded
  constellations/signals are displayed. “PVT 입력: 계산 대상” identifies eligible inputs,
  not proof that RTKLIB used that individual satellite in its final solution.
- Existing comparison tolerances remain: position 0.001 m, velocity 0.001 m/s,
  clock bias 1 ns, identical GPS week/TOW. Invalid PVT is not a successful comparison.
- RAWX standard-deviation fields are shown as device codes, not mislabeled SI values.
- COM/serial one-shot capture reuses the UBX parser and native terrestrial PVT engine.
  It retains received navigation and the first epoch with valid position AND velocity,
  with a 120-second acquisition deadline and 1 MiB input cap. Previous unsuccessful
  observation epochs are not transmitted. RAW direct transfer and I/Q transfer remain unavailable.
  Serial completion finalizes the input on the server even when the browser disconnects.
  The Agent includes its validated PVT in the completion status. The server persists it
  in the nullable `input_buffers.captured_pvt_json` column together with input completion.
  GET `/dtn/inputs/{id}/pvt` returns this saved preview in both central-server and node modes,
  including after a browser reconnect or server restart. Central mode requires the updated
  capture Agent. Inputs without a saved preview retain the existing node-only calculation
  fallback. Transmission still recalculates the exact same records independently through
  the existing sender/receiver engine.
  The collecting service will need complete navigation information plus one target epoch;
  simply stopping after the first RAWX message is not sufficient.

## Connections

- Peer IP/port is the other LNIS service, not the external adapter transfer URL.
- The green directional indicator means an explicit peer management probe responded.
  It is invalidated when the address changes. Reverse direction is not inferred.
- Agent readiness is labeled processing readiness, not DTN network connectivity.
- Four route selections reuse `/dtn/adapter-mode` through an explicit Apply button.
  Selecting a button alone does not claim the adapter applied it. Both external control
  URLs must be configured. A partial failure can leave the two adapters differently set;
  the UI reports failure and requests verification, not a false rollback/success.
- Receiver route information is marked unavailable because the current external
  transfer contract has no route metadata. It does not invent the sender's selection.

## Future contract proposal (not implemented)

Keep one trial lifecycle with three payload variants, not separate DTN/HDTN service trees.
The external adapter owns BPv7, queues, routing and transport.

1. RAW: carry a target GNSS epoch and the required navigation information without AFS.
2. AFS: preserve the existing v1 format. Pseudorange is already inside the GRAW data in
   the frames; do not pretend the current payload has a separate pseudorange field.
3. I/Q: send a file descriptor over REST, not base64 samples. Agree on a shared-root
   relative file identifier, byte size, SHA-256, duration (90 s), sampling rate, sample
   type and I/Q packing. Docker and host paths require an explicit shared-root mapping.
   Reject paths outside that root and symlinks that escape it. Publish only fully written
   files. File retention, acknowledgement, receiver file location and decoding completion
   must be agreed with the adapter developer before implementation.

HTTP acceptance is not delivery success. Define a receipt/status callback before
claiming that DTN/HDTN delivery has completed. GNSS pseudorange and network latency
remain separate quantities; multiplying DTN waiting/transit time by c is not a valid
substitute for the original GNSS measurement.

## Temporary example feature

`lnis.dtn.example-enabled` defaults to false. When enabled, `lnis.dtn.example-file`
points to an external, validated GRAW file. No example dataset is bundled in the JAR.
The button loads this file and uses the ordinary upload and AFS trial APIs. It does not
change the transfer format or add an example flag to trial records.

For production, leave the feature disabled. To remove it entirely, remove the isolated
`central/dtn/example` controller, `dtn-example.js`, the button/config hooks and the two
example configuration properties. Normal file upload, AFS/PVT calculation and tests
have no dependency on that endpoint. Synthetic fixtures used by automated tests are
not presented to users as real F9T recordings.

Actual EVK-F9T hardware validation remains required, including its firmware/protocol
version, enabled RAWX/SFRBX output and UART/USB settings.

### Verified public observation example

Run `scripts/prepare-f9t-example.ps1` on Windows with the existing native DLL available.
The source is `nav-solutions/data/UBX/F9T-L2-5min.ubx.gz` (repository license MPL-2.0),
identified as F9T by its publisher, not certified as an EVK-F9T board capture.
Source: https://github.com/nav-solutions/ubx2rinex (F9T usage example),
https://github.com/nav-solutions/data/tree/main/UBX (recording and repository license).
Pinned SHA-256: `1ABCE8A84A82B4AD9DB45362244E54CFDCA85016A79962BB02B2DFC8B5D29B87`.

This file contains 299 RAWX epochs and no SFRBX navigation. The generated example
preserves the first epoch's measured values; no synthetic ephemeris is added.
**It verifies observation transfer, not valid PVT equality.** PVT must be unavailable
and the comparison INCONCLUSIVE. Known valid synthetic GPS fixtures separately verify
the terrestrial PVT implementation and end-to-end comparison; they are not real hardware evidence.
Original PC capture timestamps and firmware version were not provided; the fixture records
an unspecified PC timestamp rather than presenting the current PC time as original capture time.

The output and verification report live in `build/dtn-example`, outside the JAR.
For the local Docker demonstration, copy the GRAW and LICENSE into the sender's
`examples` directory and use `deployment/node/docker-compose.example.yml` as an explicit
override. Starting with only the normal Compose file disables the feature. Removing
the override and example files does not affect normal uploads or AFS/PVT tests.

## AFS cancellation

AFS cancellation interrupts the session worker and checks cancellation between frame
encoding, transfer batches, synchronization scanning and decoding. A native codec call
already in progress finishes before its result is discarded; no subsequent frame is
processed for that cancelled task. Late completion callbacks and in-flight frames from
an older session cannot release or fail a newer AFS session.

## Adapter health check

The sender page provides one button beside the transfer URL. A click calls
`GET /lnis/api/v1/dtn/adapter-health`; the LNIS server probes both configured URLs
concurrently using GET (3-second connection timeout, 5-second request deadline).
Defaults are `http://192.168.1.154:8080/sender/health` and
`http://192.168.1.154:8080/receiver/health`. Override them using
`lnis.dtn.sender-adapter-health-url` and `lnis.dtn.receiver-adapter-health-url`.
These are independent of the transfer URL and mode-control URLs.

Each result includes the probed URL, HTTP status when available, elapsed milliseconds
and a message. HTTP 2xx is shown as a successful response; other statuses, timeouts
and connection failures are shown separately. No response-body schema is assumed.
Results are not cached and are labelled with their last check time, not presented as
continuous monitoring or proof of DTN delivery. Checking does not start a trial,
change adapter mode, or block transfer controls. Requests go through the LNIS server
so browser CORS and mixed-content restrictions do not affect the adapter probes.
