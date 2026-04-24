# ATClient

ATClient automates Mobile ID SIM Toolkit user interaction on a Raspberry Pi 5 with a SIMCom SIM8262E-M2 5G HAT. It is built for operational validation, unattended test setups, and watchdog aware deployments.

## Highlights

* Automate SIM Toolkit user responses, including [Mobile ID](https://www.mobileid.ch/en) authentication flows
* Configure preferred radio access technology (LTE, NR5G SA, LTE plus NR5G)
* Per heartbeat HEARTBEAT log block with band, operator and signal strength hints
* Tiered 5G SA auto recovery (COPS reselect, CFUN cycle, full modem reboot) with cooldowns
* Observe incoming text SMS activity in the application log
* Maintain watchdog heartbeats for communication health or meaningful STK activity

## Supported hardware

| Component | Recommendation |
| --- | --- |
| SIM | [Mobile ID SIM card](https://www.mobileid.ch/en) |
| Host | [Raspberry Pi 5](https://www.raspberrypi.com/products/raspberry-pi-5/) |
| Modem | [Waveshare SIM8262E-M2 5G HAT](https://www.waveshare.com/wiki/SIM8262E-M2_5G_HAT) (SIMCom SIM8262E-M2) |
| Optional | [RPi Relay Board](https://www.waveshare.com/wiki/RPi_Relay_Board) |

Tested with Raspberry Pi 5 on Debian 13 (trixie), kernel 6.12.47, OpenJDK 21, and SIMCom firmware `22131B05X62M44A-M2`.

The 5G HAT can run in LTE only, NR5G SA only, or LTE plus NR5G preferred, controlled by `simcom.network.mode` in `atclient.cfg`.

## Quick start

### Clone the repository

```bash
git clone https://github.com/phaupt/atclient.git
cd atclient/
```

### Compile

```bash
mkdir -p class
javac -d ./class -cp "./lib/*" ./src/com/swisscom/atclient/*.java
```

### Serial device detection

The stable udev symlink `/dev/simcom_at` is set up via `99-sim8262.rules.sample` (see the deployment section). Without that symlink the AT port typically shows up as `/dev/ttyUSB2`. Confirm with:

```bash
ls -l /dev/ttyUSB* /dev/simcom_at 2>/dev/null
```

### Run the application

```bash
sudo /usr/bin/java \
  -Dserial.port=/dev/simcom_at \
  -Dconfig.file=/home/mid/atclient/atclient.cfg \
  -Dlog.file=/var/log/atclient.log \
  -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml \
  -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" \
  com.swisscom.atclient.ATClient
```

### Application help

```
Usage: ATClient

        -Dconfig.file=atclient.cfg             # Application config file
        -Dlog.file=atclient.log                # Application log file
        -Dlog4j.configurationFile=log4j2.xml   # Log4j config file
        -Dserial.port=/dev/simcom_at           # Force a specific serial port
```

## Configuration files

### atclient.cfg

Main configuration file for the ATClient. Controls serial port settings, radio access technology, watchdog behaviour, and maintenance mode. Copy `atclient.cfg.sample` to `atclient.cfg` and edit to match your environment:

```bash
cp atclient.cfg.sample atclient.cfg
```

Key settings include:
* **Serial port**: port name patterns, baud rate, timeouts
* **Radio access technology**: force 2G/3G/4G or leave automatic
* **Watchdog**: communication mode (legacy) or activity mode based on meaningful STK events
* **Maintenance**: trigger a maintenance script via a Mobile ID keyword

Watchdog modes:
* `watchdog.mode=communication` (default): refresh on incoming AT RX traffic (backward-compatible legacy behavior)
* `watchdog.mode=activity`: refresh only on curated meaningful STK events (`STK019`, `STK033`, `STK035`, `STK037`, `STK254`)
* `watchdog.activity.events=<csv>`: optional STK event allowlist for activity mode (default/fallback `19,33,35,37,254`)
* `watchdog.activity.startup.grace=<ms>`: optional grace window in milliseconds for activity mode; during grace, communication RX still refreshes watchdog

Meaningful STK activity is derived from the SIMCom `+STIN:` proactive command URCs. ATClient parses those command codes and logs them as `STKxxx` markers. On the 5G HAT, Mobile ID auth emits `+STIN: 21` (DISPLAY TEXT), `+STIN: 23` (GET INPUT) and `+STIN: 25` (SET UP MENU), which is also re emitted as the post session return to idle marker.

Recommended production starting point:
* `watchdog.file=/var/log/watchdog.atclient` (single productive watchdog file path)
* `watchdog.mode=activity`
* `watchdog.activity.events=19`
* `watchdog.activity.startup.grace=300000` (5 minutes, milliseconds)
* Linux watchdog `change=900` (15 minutes, seconds) as a conservative starting point in `/etc/watchdog.conf`

`atclient.cfg.sample` already uses that production-oriented activity baseline, while keeping `watchdog.enable=false` by default for safer local/manual testing.

Host-side Linux watchdog must also be configured (ATClient only updates the file):

```ini
# /etc/watchdog.conf
file = /var/log/watchdog.atclient
change = 900
```

Example (real Raspberry Pi deployment, shortened):

```ini
# /etc/watchdog.conf
file            = /var/log/watchdog.atclient
change          = 900
max-load-1      = 24
min-memory      = 1
watchdog-device = /dev/watchdog
max-temperature = 65
interval        = 1
realtime        = yes
priority        = 1
watchdog-timeout = 15
```

Notes on the example above:
* `file` and `change` define the ATClient heartbeat expectation window (15 minutes)
* `interval=1`, `realtime=yes`, and `priority=1` make watchdog checks frequent and scheduling robust under load
* load/memory/temperature/device checks run in parallel to file-change monitoring

Why `change=900` is a conservative starting point:
* healthy Mobile ID activity is usually expected about every 5 minutes
* a failed attempt may take about 60-80 seconds before timeout
* retry cadence in error mode is often around 3 minutes
* startup/modem attach/SIM registration need additional safety margin
* less aggressive than 10-12 minute settings during temporary carrier/network issues

Migration note for older setups:
* recommended target state now uses only `/var/log/watchdog.atclient` as productive file
* `/home/mid/atclient/watchdog.atclient` is legacy and should be migrated
* `/var/log/watchdog-keywordcheck.atclient` is historical keyword-check path, not a parallel production model

### log4j2.xml

Log4j2 configuration for application logging. Controls log output format, file rotation, and log levels. Copy the sample and adjust as needed:

```bash
cp log4j2.xml.sample log4j2.xml
```

The default configuration writes logs to the file specified by `-Dlog.file` with a 500 MB rotation policy.

## Keywords

If a keyword (case sensitive) is found in the Mobile ID authentication message, the ATClient will invoke specific actions:

| Keyword | Action |
|---------|--------|
| `CANCEL` | TerminalResponse `16` — Proactive SIM session terminated by user |
| `STKTIMEOUT` | TerminalResponse `18` — No response from user |
| `USERDELAY=x` | Delay the first TerminalResponse by x seconds (1–9) |
| `BLOCKPIN` | Block the Mobile ID PIN |
| `RAT=x` | Set Radio Access Technology (`A`=Auto, `0`=2G, `2`=3G, `7`=4G) |
| `REBOOT` | Execute `reboot` (requires root) |
| `MAINTENANCE` | Execute maintenance shell script (requires root) |

## Auto start at boot

On Raspberry Pi OS you can configure ATClient to auto-start at boot. The recommended approach is a systemd service:

```ini
[Unit]
Description=ATClient Mobile ID responder
After=network.target

[Service]
Type=simple
ExecStartPre=/bin/sleep 80
ExecStart=/usr/bin/java -Dconfig.file=/home/mid/atclient/atclient.cfg -Dlog.file=/var/log/atclient.log -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" com.swisscom.atclient.ATClient
Restart=on-failure
User=root

[Install]
WantedBy=multi-user.target
```

The 80 second sleep gives the 5G HAT time to enumerate its USB interfaces and complete initial radio probing before atclient opens the AT port.

Alternatively, you can use `/etc/rc.local` (add before `exit 0`):

```bash
(/bin/sleep 80 && /usr/bin/java -Dconfig.file=/home/mid/atclient/atclient.cfg -Dlog.file=/var/log/atclient.log -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" com.swisscom.atclient.ATClient) &
```

Example boot flow from a field Raspberry Pi setup (`/etc/rc.local`, shortened):

```bash
# avoid early watchdog reboot before Java process starts
touch /var/log/watchdog.atclient

# optional local hardware helpers
/home/mid/oled-i2c/mid.sh 600 &
/home/mid/relay.sh CH3 ON

# start ATClient after modem boot delay
(/bin/sleep 80 && /usr/bin/java \
  -Dserial.port=/dev/ttyACM1 \
  -Dconfig.file=/home/mid/atclient/atclient.cfg \
  -Dlog.file=/var/log/atclient.log \
  -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml \
  -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" \
  com.swisscom.atclient.ATClient) &
```

This sequence highlights the practical dependency chain:
1. create initial watchdog heartbeat file
2. power/initialize attached hardware
3. wait for LTE terminal readiness
4. start Java client with explicit serial port and config paths

## SIMCom SIM8262E-M2 5G HAT deployment (Raspberry Pi 5)

End-to-end bring-up sequence for the Waveshare SIM8262E-M2 5G HAT on a Raspberry Pi 5 with Debian 13 (trixie). Validated on 2026-04-18 with a 10-cycle Mobile-ID SIGNATURE regression.

### Hardware prerequisites

- Raspberry Pi 5 with official 27 W USB-C PSU
- Waveshare SIM8262E-M2 5G HAT, power switch set to **external**, powered via its own USB-C 5 V / 3 A PSU (the HAT's 1.8 A peak TX draws exceed the Pi's USB budget)
- 4 SMA antennas connected (the wiki labels ANT3 as the GNSS port; all 4 remain connected regardless)
- USB-A to USB-A cable from Pi to HAT for the data path
- 2x20 pin header soldered to the Pi GPIO if you plan to use any GPIO power/reset control (optional: all data goes through USB)
- DIP switches: top block (D5/D4/TX/RX/D6) all **OFF**; bottom "B" row all **ON** (mandatory for SIM82XX family)
- Nano-SIM with Mobile-ID or 5G-capable subscription in slot 1 (chip facing up, cut corner first)

### OS and base packages

```bash
sudo apt update
sudo apt install -y default-jdk libqmi-utils
```

### udev rule for stable serial symlinks

Copy the sample and reload udev so the AT port is accessible at `/dev/simcom_at` regardless of the (unstable) `ttyUSBN` numbering:

```bash
sudo cp 99-sim8262.rules.sample /etc/udev/rules.d/99-sim8262.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=tty
ls -la /dev/simcom_at /dev/simcom_modem
```

Verify the interface number matches the one the rule pins (expected `02` for AT, `03` for modem):

```bash
udevadm info -q property -n /dev/ttyUSB2 | grep ID_USB_INTERFACE_NUM
```

### Disable ModemManager

ModemManager competes for the QMI control port and can confuse the STK flow. Disable it so the data-plane service owns `/dev/cdc-wdm0` cleanly:

```bash
sudo systemctl disable --now ModemManager
```

### Provision STK (one-shot)

Enable SIM Toolkit and decoded output on the module. This requires a modem reboot (`AT+CFUN=1,1`) and only needs to be done once per device:

```bash
python3 <<'EOF'
import serial, time
s = serial.Serial("/dev/simcom_at", 115200, timeout=3)
s.write(b"AT+STK=1\r\n");     time.sleep(1)
s.write(b"AT+STKFMT=0\r\n");  time.sleep(1)
s.write(b"AT+CFUN=1,1\r\n");  time.sleep(1)
s.close()
EOF
sleep 30   # wait for modem to come back up
```

### Configure ATClient

```bash
git clone https://github.com/phaupt/atclient.git /home/phaupt/atclient
cd /home/phaupt/atclient
git checkout feat/sim8262e-modem-driver   # until merged
mkdir -p class
javac -proc:none -d ./class -cp "./lib/*" ./src/com/swisscom/atclient/*.java
cp atclient.cfg.sample atclient.cfg
cp log4j2.xml.sample log4j2.xml
sudo touch /var/log/watchdog.atclient && sudo chmod 666 /var/log/watchdog.atclient
```

Review `atclient.cfg` and adjust any site-specific paths (APN, data, maintenance).

### QMI data path (optional: for wwan0 + autossh tunnels)

If the deployment needs an IP data connection over cellular (reverse-tunnel, remote-access), deploy the QMI systemd unit:

```bash
sudo cp modem-data.service.sample /etc/systemd/system/modem-data.service
# Review APN value in the unit, then:
sudo systemctl daemon-reload
sudo systemctl enable --now modem-data.service
journalctl -u modem-data -n 20
```

For the reverse tunnel back to a jumphost, copy `autossh-simcom.service.sample` as `autossh-simcom.service`, adjust `JUMPHOST` / `JUMP_USER` / `REMOTE_PORT`, and enable.

### Deploy ATClient as a systemd service

```bash
sudo cp atclient.service.sample /etc/systemd/system/atclient.service
# Edit paths (default -Dserial.port=/dev/simcom_at, WorkingDirectory, log paths).
sudo systemctl daemon-reload
sudo systemctl enable --now atclient.service
journalctl -u atclient -f
```

Expected startup log lines:

```
Modem driver selected: SIMCom SIM8262E-M2 (modem.type=simcom)
REG: CEREG stat=1 (home-registered)
REG: C5GREG stat=0 (not-registered)       # SA not entitled on most Mobile-ID SIMs
RADIOT: E-UTRAN (4G/LTE)                  # or RADIOT: EN-DC / NR5G in areas with 5G coverage
RADIOQ: +CPSI mode=LTE ... rsrq=... rsrp=... rssi=... sinr=...
Startup readiness confirmed after attempt 1.
```

### Triggering Mobile-ID auth

From the backend host (not the Pi):

```bash
ssh mobileid
cd mobileid-midlab/shell/
./mobileid-sign.sh -t JSON -d <MSISDN> "Test" en
```

On the Pi the log should show the full cycle end-to-end:

```
STK033: DISPLAY TEXT
UI-TXT: 'Test'
STK035: GET INPUT
UI-TXT: 'Authenticate with your Mobile ID PIN'
STK037: SET UP MENU                       # SIMCom's return-to-main marker
RADIOT: E-UTRAN (4G/LTE)                  # post-session radio refresh
```

Backend receives `StatusCode 500 SIGNATURE` on success.

## Logging and monitoring

Follow the application log in real time:

```bash
tail -F /var/log/atclient.log
```

Search for errors:

```bash
grep -i err /var/log/atclient.log
```

## How to read a sample atclient.log

The excerpt below is shortened and sanitized from a real Raspberry Pi deployment log. MSISDNs, IMSIs, OTPs, and other identifying values are redacted or omitted. It preserves the logical order of events but skips many lines, so the timestamps are examples from one run, not a timing benchmark.

Real logs may also contain identifier lookups such as `AT+CNUM` or `AT+CIMI`. Do not publish those values without redaction.

```text
2026-03-13 08:56:37 [INF] Reading Property file at /home/mid/atclient/atclient.cfg
2026-03-13 08:56:37 [INF] Start serial port initialization.
2026-03-13 08:56:37 [INF] Found a serial port: ttyAMA0 'Physical Port AMA0' - but this isn't matching -Dserial.port=/dev/ttyACM1
2026-03-13 08:56:50 [INF] Found a serial port: ttyACM1 'LTE Modem'
2026-03-13 10:08:46 [INF] ttyACM1 connection established. Let's see if it responds to AT commands.
2026-03-13 10:08:46 [DBG] TX0 >>> AT
2026-03-13 10:08:46 [DBG] RX2 <<< OK
2026-03-13 10:08:49 [DBG] TX0 >>> AT+CPIN?
2026-03-13 10:08:49 [DBG] RX2 <<< +CPIN: READY
2026-03-13 10:08:56 [DBG] TX0 >>> AT+COPS?
2026-03-13 10:08:56 [DBG] RX2 <<< +COPS: 0
2026-03-13 10:08:56 [DBG] TX0 >>> AT+CSQ
2026-03-13 10:08:57 [DBG] RX2 <<< +CSQ: 99,99
2026-03-13 10:08:58 [INF] STK037: SET UP MENU
2026-03-13 10:08:58 [INF] UI-TXT: 'SIM Apps'
2026-03-13 10:08:58 [INF] UI-TXT: 'Mobile ID'
2026-03-13 10:08:59 [INF] STK254: SIM Applet returns to main menu
...
2026-03-13 10:10:42 [INF] STK019: SEND MESSAGE
2026-03-13 10:10:44 [INF] STK254: SIM Applet returns to main menu
2026-03-13 10:10:58 [DBG] RX1 <<< +CMTI: "SM", 0
2026-03-13 10:10:58 [INF] TEXT MESSAGE (SMS)
2026-03-13 10:10:58 [DBG] TX0 >>> AT+CMGR=0
2026-03-13 10:10:59 [DBG] RX2 <<< +CMGR: "REC UNREAD","<REDACTED-MSISDN>",,"26/03/13,10:10:57+04"
2026-03-13 10:10:59 [DBG] RX2 <<< Your unique identification code is: <REDACTED-OTP>. Please enter the code in the web interface to continue.
2026-03-13 10:10:59 [DBG] TX0 >>> AT+CMGD=0,4
...
2026-03-13 10:11:17 [INF] STK035: GET INPUT
2026-03-13 10:11:18 [INF] UI-TXT: 'Define your new Mobile ID PIN (6 digits)'
2026-03-13 10:11:19 [INF] STK035: GET INPUT
2026-03-13 10:11:19 [INF] UI-TXT: 'Confirm your new Mobile ID PIN'
2026-03-13 10:11:27 [INF] STK019: SEND MESSAGE
2026-03-13 10:11:29 [INF] STK019: SEND MESSAGE
2026-03-13 10:11:31 [INF] STK019: SEND MESSAGE
2026-03-13 10:11:33 [INF] STK254: SIM Applet returns to main menu
...
2026-03-13 10:11:43 [INF] STK033: DISPLAY TEXT
2026-03-13 10:11:43 [INF] UI-TXT: 'Please confirm the test request of the Mobile ID Selfcare Portal'
2026-03-13 10:11:44 [INF] STK035: GET INPUT
2026-03-13 10:11:45 [INF] UI-TXT: 'Authenticate with your Mobile ID PIN'
2026-03-13 10:11:46 [INF] STK019: SEND MESSAGE
2026-03-13 10:11:48 [INF] STK254: SIM Applet returns to main menu
```

1. **Startup and serial port discovery**: The client reads its configuration, starts serial port initialization, ignores ports that do not match the configured modem device, and keeps checking until the expected serial interface appears. Once the matching modem port is available, it opens the port and sends `AT` to confirm that the modem responds.
2. **Basic modem and SIM initialization**: `AT` is the basic modem handshake. `AT+CPIN?` checks whether the SIM is ready or still expects PIN entry. `AT+COPS?` reports operator selection and registration state, and `AT+CSQ` reports signal quality. During startup, ATClient now performs a bounded readiness retry loop for these checks before entering steady-state processing; if readiness is not reached in time, it exits cleanly instead of continuing in a partial state. For plain text SMS, `+CMTI` announces an incoming message, `AT+CMGR=<slot>` reads it, and `AT+CMGD=0,4` deletes stored SMS after processing.
3. **SIM Toolkit initialization**: `STK037: SET UP MENU` followed by `UI-TXT: 'SIM Apps'` and `UI-TXT: 'Mobile ID'` shows the SIM Toolkit applet presenting its top-level menu through the modem/terminal interface. `STK254: SIM Applet returns to main menu` is the visible sign that the applet has returned to its idle or main-menu state.
4. **Ping / reachability test**: In this setup, a backend-triggered binary or PDU-style SMS can lead to `STK019: SEND MESSAGE`. The log does not necessarily expose the full logical payload of that backend transaction; what it reliably shows is that the SIM Toolkit session caused an outgoing message to be sent.
5. **OTP text SMS**: The `+CMTI` notification, `TEXT MESSAGE (SMS)`, `AT+CMGR`, and the SMS body shown in debug RX lines represent a normal text-SMS path. This is much more directly visible than SIM Toolkit driven traffic because the sender metadata and SMS body can be read from the modem. Depending on ATClient version and log level, the full SMS body may be shown only at `DEBUG`.
6. **Account enrollment flow**: The incoming backend trigger happens outside the visible SIM internals, but the log clearly shows the user-facing prompts `Define your new Mobile ID PIN (6 digits)` and `Confirm your new Mobile ID PIN`. The later `STK019: SEND MESSAGE` lines show outbound responses from the SIM/terminal layer. The internal applet logic, including sensitive work such as key generation, is not directly visible in the log. Multiple `STK019` events can occur because the response may be split across multiple mobile-originated SMS segments.
7. **Authentication flow**: `STK033: DISPLAY TEXT` shows the confirmation message, `STK035: GET INPUT` shows that the terminal is asked for PIN input, and the later `STK019: SEND MESSAGE` marks the outbound response. The sensitive authentication or signature logic runs inside the SIM; the ATClient only sees the terminal-facing prompts and the resulting message trigger.

Together these phases show why the ATClient is useful: from the Raspberry Pi side, it makes the modem link, network status, incoming text SMS handling, and terminal-visible SIM Toolkit activity observable without claiming visibility into the SIM's private cryptographic internals.

The `+C...` commands above are standardized 3GPP-style AT commands. The `^SST...` commands are modem or vendor-specific SIM Toolkit related commands used in this setup, so this README treats them as observable markers rather than definitive vendor-internal semantics.

### What you can learn from the log

* Whether the correct serial port was selected
* Whether the modem responds to basic `AT` communication
* Whether the SIM is ready
* Whether operator selection and signal information are available
* Whether SIM Toolkit events are being received
* Whether plain text SMS handling and STK-triggered outbound messaging are happening

### What the log does not show

* Internal cryptographic operations performed on the SIM
* The full business semantics of binary or SIM-directed payloads
* The complete internal logic of the Mobile ID SIM applet

### Short glossary

| Term | Meaning in this log |
|---|---|
| `CPIN` | SIM or PIN readiness check |
| `COPS` | Operator selection / network registration status |
| `CSQ` | Signal quality |
| `CMTI` | Notification that a text SMS was stored |
| `CMGR` | Read a stored SMS |
| `CMGD` | Delete stored SMS |
| `STK033` | Display text from the SIM Toolkit session |
| `STK035` | Prompt for user input |
| `STK037` | Set up the SIM Toolkit menu |
| `STK019` | Request to send a message |
| `STK254` | Applet has returned to its idle or main-menu state |

## Watchdog note for local testing

In productive deployments, a watchdog may be used to automatically reboot the device if expected activity stops. During local testing, this can interfere with manual work by triggering unexpected reboots.

During manual tests and debugging, stopping the Linux watchdog service is often intentional to avoid unwanted reboot loops.

Before doing manual tests, check and optionally stop the watchdog:

```bash
sudo systemctl status watchdog
sudo systemctl stop watchdog
```

Remember to re-enable it when you are done testing.

### Raspberry Pi smoke test for activity mode (`STK019` only)

1. Configure ATClient:
   * `watchdog.enable=true`
   * `watchdog.file=/var/log/watchdog.atclient`
   * `watchdog.mode=activity`
   * `watchdog.activity.events=19`
   * `watchdog.activity.startup.grace=300000`
2. Configure Linux watchdog to monitor the same file and set `change=900` in `/etc/watchdog.conf` (conservative starting point).
3. Start/restart ATClient and follow the log:
   * `tail -F /var/log/atclient.log`
4. Trigger a Mobile ID authentication from your monitoring/test backend.
5. Verify expected markers:
   * log contains `STK019: SEND MESSAGE`
   * watchdog file timestamp/content updates shortly after `STK019`
6. Verify negative case:
   * if no `STK019` occurs for longer than the Linux watchdog `change` window, recovery action is expected.

Useful host-side commands:
* `sudo systemctl status watchdog`
* `sudo systemctl stop watchdog`
* `sudo systemctl start watchdog`
* `stat /var/log/watchdog.atclient`

## Optional: OLED status handling on Raspberry Pi

If you run the optional OLED helper on Raspberry Pi (`/home/mid/oled-i2c`), you can apply a small improvement so the first line no longer stays on `AT starts soon...` when ATClient is already running.

This is optional and mainly useful for Pi units with OLED. On headless units, you can skip this section.

OLED line 1 behavior after this change:
* `AT starts soon...` -> ATClient process is not running yet
* `AT error state` -> watchdog file contains `ERR`, `FAIL`, or `NOT READY`
* `AT running / waiting` -> ATClient is running, but no STK heartbeat data is available yet (or watchdog is disabled)
* otherwise -> normal status line (`RAT / signal / age`)

Before editing, create backups on the Pi:

```bash
cd /home/mid/oled-i2c
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
cp get-age.sh get-age.sh.bak_$TIMESTAMP
cp mid.py mid.py.bak_$TIMESTAMP
```

Set `/home/mid/oled-i2c/get-age.sh` to:

```bash
#! /bin/bash

if [ ! -e /var/log/watchdog.atclient ]
then
    echo "n/a"
    exit 1
fi

filecontent=$(cat /var/log/watchdog.atclient)

# Check for error markers in full file content
if echo "$filecontent" | grep -qi "ERR\|FAIL\|NOT READY"
then
    echo "ERR"
    exit 0
fi

date1=$(echo "$filecontent" | cut -d "," -f1)

# If no valid content yet, check if ATClient is at least running
if [ -z "$date1" ]
then
    if pgrep -f "com.swisscom.atclient.ATClient" > /dev/null 2>&1
    then
        echo "ready"
    else
        echo "n/a"
    fi
    exit 0
fi

seconds1=$(date --date "$date1" +%s)
seconds2=$(date +%s)
delta=$((seconds2 - seconds1))

if [[ "$delta" -lt 86400 ]]; then
    echo "${delta}"
else
    echo ">1 day"
fi
```

Then restore execute permission:

```bash
chmod +x /home/mid/oled-i2c/get-age.sh
```

Set `/home/mid/oled-i2c/mid.py` to:

```python
import time
import sys
sys.path.append('/home/mid/oled-i2c/drive')
import SPI
import SSD1305

from PIL import Image
from PIL import ImageDraw
from PIL import ImageFont

import subprocess

# Raspberry Pi pin configuration:
RST = None     # on the PiOLED this pin isnt used
# Note the following are only used with SPI:
DC = 24
SPI_PORT = 0
SPI_DEVICE = 0

# 128x32 display with hardware I2C:
disp = SSD1305.SSD1305_128_32(rst=RST)

# Initialize library.
disp.begin()

# Clear display.
disp.clear()
disp.display()

# Create blank image for drawing.
# Make sure to create image with mode '1' for 1-bit color.
width = disp.width
height = disp.height
image = Image.new('1', (width, height))
# Get drawing object to draw on image.
draw = ImageDraw.Draw(image)

# Draw a black filled box to clear the image.
draw.rectangle((0,0,width,height), outline=0, fill=0)

# Draw some shapes.
# First define some constants to allow easy resizing of shapes.
padding = 0
top = padding
bottom = height-padding
# Move left to right keeping track of the current x position for drawing shapes.
x = 0

# Alternatively load a TTF font.  Make sure the .ttf font file is in the same directory as the python script!
# Some other nice fonts to try: http://www.dafont.com/bitmap.php
DefaultFont = ImageFont.truetype('/home/mid/oled-i2c/04B_08__.TTF',8)
BigFont = ImageFont.truetype('/home/mid/oled-i2c/04B_08__.TTF',14)

startTime = time.time()
timeout = int(sys.argv[1]) # take the command line argument (timeout in seconds)

# Clear display
draw.rectangle((0,0,width,height), outline=0, fill=0)
disp.clear()
disp.display()

while True:

    cmd = "date +'%d.%m.%y %H:%M' | tr -d '\n'"
    Uptim = subprocess.check_output(cmd, shell = True )

    # Draw a black filled box to clear the image.
    draw.rectangle((0,0,width,height), outline=0, fill=0)

    # /var/log/watchdog.atclient columns:
    # 2020-05-12 09:38:55,+41796691039,Swisscom,4G,61%
    #                   1,           2,       3, 4,  5

    # time critical commands first!
    cmd = "date +'%d.%m.%y %H:%M' | tr -d '\n'"
    Date = subprocess.check_output(cmd, shell = True )

    cmd = "/home/mid/oled-i2c/get-age.sh | tr -d '\n'"
    LastStkHeartbeat = subprocess.check_output(cmd, shell = True )

    #cmd = "awk '{printf(\"%02d:%02d\",($1/60/60%24),($1/60%60))}' /proc/uptime | tr -d '\n'"
    cmd = "awk '{printf(\"%03d %02d\",($1/60/60/24),($1/60/60%24))}' /proc/uptime | tr -d '\n'"
    Uptime = subprocess.check_output(cmd, shell = True )

    # now do the rest..
    cmd = "hostname -I | cut -d\' \' -f1 | tr -d '\n'"
    IP = subprocess.check_output(cmd, shell = True )

    cmd = "hostname | cut -c 9-11 | tr -d '\n'"
    Hostname = subprocess.check_output(cmd, shell = True )

    cmd = "cut -d ',' -f2 /var/log/watchdog.atclient | sed 's/\+//g' | tr -d '\n'"
    MSISDN = subprocess.check_output(cmd, shell = True )

    cmd = "cut -d ',' -f4 /var/log/watchdog.atclient | tr -d '\n'"
    RAT = subprocess.check_output(cmd, shell = True )

    cmd = "cut -d ',' -f5 /var/log/watchdog.atclient | tr -d '\n'"
    SignalStrengthPercentage = subprocess.check_output(cmd, shell = True )

    cmd = "cut -d ',' -f6 /var/log/watchdog.atclient | tr -d '\n'"
    SignalStrengthIcon = subprocess.check_output(cmd, shell = True )

    cmd = "cut -d ',' -f3 /var/log/watchdog.atclient | tr -d '\n'"
    Operator = subprocess.check_output(cmd, shell = True )

    if "n/a" in LastStkHeartbeat:
        draw.text((x, top), "AT starts soon...", font=DefaultFont, fill=255)
    elif "ERR" in LastStkHeartbeat:
        draw.text((x, top), "AT error state", font=DefaultFont, fill=255)
    elif "ready" in LastStkHeartbeat:
        draw.text((x, top), "AT running / waiting", font=DefaultFont, fill=255)
    else:
        draw.text((x, top), str(RAT) + " " + str(SignalStrengthPercentage) + " " + str(SignalStrengthIcon) + " AGE " + str(LastStkHeartbeat),  font=DefaultFont, fill=255)
    draw.text((x, top+8), str(MSISDN) + " " + str(Operator), font=DefaultFont, fill=255)
    draw.text((x, top+16), "ID=" + str(Hostname) + "  IP=" + str(IP),  font=DefaultFont, fill=255)
    draw.text((x, top+25), str(Date) + " UP " + str(Uptime),  font=DefaultFont, fill=255)

    # Display image.
    disp.image(image)
    disp.display()
    time.sleep(.1)

    if (time.time() - startTime) > timeout:
        break

# Clear display
draw.rectangle((0,0,width,height), outline=255, fill=255)
disp.clear()
disp.display()

# Terminate
sys.exit()
```

After applying both files, either reboot or restart the OLED script:

```bash
pkill -f "/home/mid/oled-i2c/mid.py"
nohup /home/mid/oled-i2c/mid.sh 600 >/dev/null 2>&1 &
```

Expected result:
* before ATClient start -> `AT starts soon...`
* ATClient running without STK activity yet -> `AT running / waiting`
* error condition -> `AT error state`
* valid watchdog CSV with STK activity -> normal `RAT / signal / age` status line

## Troubleshooting

| Problem | What to check |
|---------|--------------|
| Serial device not found | Run `ls -l /dev/ttyACM*` — is the terminal plugged in and powered? Check `dmesg` for USB enumeration. |
| Wrong ACM port selected | The modem may appear on a different `/dev/ttyACMx` after reboot. Use `-Dserial.port=` to select the correct one, or rely on auto-detection. |
| Logs stay empty | Verify that `-Dlog.file` points to a writable path and that `log4j2.xml` is configured correctly. Check file permissions. |
| Watchdog interferes with testing | Stop the watchdog service before manual tests: `sudo systemctl stop watchdog` |
| Need to verify basic AT communication | Open the serial port directly: `sudo screen /dev/ttyACM1 9600` — type `AT` and expect `OK`. Press `Ctrl-A` then `K` to quit screen. |

## Optional: manual modem test

To verify basic AT communication independently of the Java client, open the serial device directly:

```bash
sudo screen /dev/ttyACM1 9600
```

Type `AT` and press Enter. If the modem responds with `OK`, the serial connection is working. Use `Ctrl-A` then `K` to exit screen.

This can help isolate whether a problem is in the serial connection or in the ATClient application itself.

## License

This project is licensed under the Apache License 2.0 — see [LICENSE](LICENSE) for details.

Third-party dependencies bundled in `lib/` are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
