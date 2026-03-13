# atclient

Automate Mobile ID (SIM Toolkit) user response.

![ATClient Hardware](img/RPi4-FullUnitWithRelay_small.png?raw=true)

## Features

* Auto-respond to SIM Toolkit requests, for example a [Mobile ID](https://www.mobileid.ch/en) authentication request
* Auto detect the SIM terminal (USB port)
* Configuration of the radio access technology (4G/3G/2G)
* Forward incoming Text SMS (if they match a configured pattern) to a configured target MSISDN
* Publish incoming Text SMS (if they match a configured pattern) to a URL
* Write watchdog file upon every successful AT communication

## What you will need

Recommended setup:

* [Mobile ID SIM card](https://www.mobileid.ch/en)
* [Raspberry Pi 4](https://www.raspberrypi.com/products/raspberry-pi-4-model-b/)
* [PLS8-E LTE terminal](https://www.hcp.rs/en/products/communications-/hit-u4-lte)
* [RPi Relay Board](https://www.waveshare.com/wiki/RPi_Relay_Board) (optional)

## Wireless terminal

This application has been tested with Raspberry Pi 4 and PLS8-E LTE terminal ([HCP HIT U4 LTE](https://www.hcp.rs/en/products/communications-/hit-u4-lte)).

> **Note:** Official Cinterion / Thales technical documentation and Windows drivers are no longer published on the old public Gemalto URLs.
> For current documentation and support, use:
> - [Thales/Gemalto Support Portal](https://supportportal.gemalto.com/csm?id=kb_home_page)
> - [HCP Support](https://www.hcp.rs/en/support/communications)
> - [HCP Contact](https://www.hcp.rs/en/contact-us-)

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

The wireless modem may appear as different ACM devices depending on setup and boot order:

```bash
ls -l /dev/ttyACM*
```

You may see `/dev/ttyACM0`, `/dev/ttyACM1`, or `/dev/ttyACM2`. To identify which port belongs to the modem, unplug the terminal, run `sudo dmesg -c`, plug it back in, wait a few seconds, then run `sudo dmesg`.

### Run the application

#### First run: switch to Explicit Response (ER) mode

As a first step, switch the terminal from factory default Automatic Response (AR) mode to Explicit Response (ER) mode:

```bash
sudo /usr/bin/java \
  -Dserial.port=/dev/ttyACM1 \
  -Dconfig.file=/home/mid/atclient/atclient.cfg \
  -Dlog.file=/var/log/atclient.log \
  -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml \
  -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" \
  com.swisscom.atclient.ATClient ER
```

#### Normal operation: user emulation

Once your terminal is in ER mode, run the ATClient in normal mode:

```bash
sudo /usr/bin/java \
  -Dserial.port=/dev/ttyACM1 \
  -Dconfig.file=/home/mid/atclient/atclient.cfg \
  -Dlog.file=/var/log/atclient.log \
  -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml \
  -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" \
  com.swisscom.atclient.ATClient
```

#### Application help

```
Usage: ATClient [<MODE>]

<MODE>  Switch operation mode:
        ER      Switch to Explicit Response (ER) and enable modem usage.
        AR      Switch to Automatic Response (AR) and reset AT command settings to factory default values.

If no <MODE> argument found: Run user emulation with automatic serial port detection (ER operation mode only)

        -Dlog.file=atclient.log                # Application log file
        -Dlog4j.configurationFile=log4j2.xml   # Location of log4j2.xml configuration file
        -Dserial.port=/dev/ttyACM1             # Select specific serial port (no automatic port detection)
```

## Configuration files

### atclient.cfg

Main configuration file for the ATClient. Controls serial port settings, radio access technology, SMS forwarding/publishing rules, watchdog behaviour, and maintenance mode. Copy `atclient.cfg.sample` to `atclient.cfg` and edit to match your environment:

```bash
cp atclient.cfg.sample atclient.cfg
```

Key settings include:
* **Serial port**: port name patterns, baud rate, timeouts
* **Radio access technology**: force 2G/3G/4G or leave automatic
* **SMS forwarding**: enable forwarding of matching SMS to a target MSISDN
* **SMS publishing**: publish matching SMS content to a URL endpoint
* **Watchdog**: write a watchdog file on successful AT communication
* **Maintenance**: trigger a maintenance script via a Mobile ID keyword

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

The 80 second sleep is recommended because the PLS8-E LTE terminal requires 30–40 seconds to boot.

Alternatively, you can use `/etc/rc.local` (add before `exit 0`):

```bash
(/bin/sleep 80 && /usr/bin/java -Dconfig.file=/home/mid/atclient/atclient.cfg -Dlog.file=/var/log/atclient.log -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" com.swisscom.atclient.ATClient) &
```

## Logging and monitoring

Follow the application log in real time:

```bash
tail -F /var/log/atclient.log
```

Search for errors:

```bash
grep -i err /var/log/atclient.log
```

## Watchdog note for local testing

In productive deployments, a watchdog may be used to automatically reboot the device if expected activity stops. During local testing, this can interfere with manual work by triggering unexpected reboots.

Before doing manual tests, check and optionally stop the watchdog:

```bash
sudo systemctl status watchdog
sudo systemctl stop watchdog
```

Remember to re-enable it when you are done testing.

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
