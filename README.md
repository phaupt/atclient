# atclient
Automate Mobile ID (SIM Toolkit) user response.

![ATClient Hardware](img/RPi4-FullUnitWithRelay_small.png?raw=true)

#### Features

* Auto-respond to SIM Toolkit request, for example a [Mobile ID](https://www.mobileid.ch/en) authentication request
* Auto detect the SIM terminal (USB port)
* Configuration of the radio access technology (4G/3G/2G)
* Forward incoming Text SMS (if they match a configured pattern) to a configured target MSISDN
* Publish incoming Text SMS (if they match a configured pattern) to a URL
* Write watchdog file upon every successful AT communication

### What you will need
Recommended setup:
* [Mobile ID SIM card](https://www.mobileid.ch/en)
* [Raspberry Pi 4](https://www.raspberrypi.com/products/raspberry-pi-4-model-b/)
* [PLS8-E LTE terminal](https://www.hcp.rs/en/products/communications-/hit-u4-lte)
* [RPi Relay Board](https://www.waveshare.com/wiki/RPi_Relay_Board)

### Wireless terminal

This application has been tested with Raspberry Pi 4 and PLS8-E LTE terminal ([HCP HIT U4 LTE](https://www.hcp.rs/en/products/communications-/hit-u4-lte)).

> **Note:** Official Cinterion / Thales technical documentation and Windows drivers are no longer published on the old public Gemalto URLs.
> For current documentation and support, use:
> - [Thales/Gemalto Support Portal](https://supportportal.gemalto.com/csm?id=kb_home_page)
> - [HCP Support](https://www.hcp.rs/en/support/communications)
> - [HCP Contact](https://www.hcp.rs/en/contact-us-)

### Serial port

To find serial port details on Raspberry Pi OS:

1. Unplug the GSM terminal
2. Run: `sudo dmesg -c`
3. Plug in the GSM terminal and wait a few seconds
4. Run: `sudo dmesg`

### How To

#### Clone Git repository
`pi@raspberypi:~ $ git clone https://github.com/phaupt/atclient.git`

#### Compile java source
```
pi@raspberypi:~/atclient $ mkdir class
pi@raspberypi:~/atclient $ javac -d ./class -cp "./lib/*" ./src/com/swisscom/atclient/*.java
```

#### ATClient Configuration

Edit the `atclient.cfg` to configure the ATClient parameters.

#### Run the application

##### Application help
```
pi@raspberypi:~ $ /usr/bin/java -Dconfig.file=/home/mid/atclient/atclient.cfg -Dlog.file=/home/mid/atclient/atclient.log -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" com.swisscom.atclient.ATClient --help

*** AT Client ***

Usage: ATClient [<MODE>]

<MODE>  Switch operation mode:
        ER      Switch to Explicit Response (ER) and enable modem usage.
        AR      Switch to Automatic Response (AR) and reset AT command settings to factory default values.

If no <MODE> argument found: Run user emulation with automatic serial port detection (ER operation mode only)

        -Dlog.file=atclient.log                # Application log file
        -Dlog4j.configurationFile=log4j2.xml   # Location of log4j.xml configuration file
        -Dserial.port=/dev/ttyACM1             # Select specific serial port (no automatic port detection)
```

##### Switch to Explicit Response (ER) mode

As a first step, you must switch the terminal from factory default Automatic Response (AR) mode to Explicit Response (ER) mode.

`pi@raspberypi:~ $ /usr/bin/java -Dserial.port=/dev/ttyACM1 -Dconfig.file=/home/mid/atclient/atclient.cfg -Dlog.file=/home/mid/atclient/atclient.log -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" com.swisscom.atclient.ATClient ER`

##### User Emulation

Once your terminal is in Explicit Response (ER) mode you can run the ATClient program in normal mode:

`pi@raspberypi:~ $ /usr/bin/java -Dconfig.file=/home/mid/atclient/atclient.cfg -Dlog.file=/home/mid/atclient/atclient.log -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" com.swisscom.atclient.ATClient`

##### Keywords

If a keyword (case sensitive) is found in the Mobile ID authentication message, the ATClient will invoke specific actions.

`'CANCEL'       : TerminalResponse '16' - Proactive SIM session terminated by user.`

`'STKTIMEOUT'   : TerminalResponse '18' - No response from user.`

`'USERDELAY=x'  : The very first TerminalResponse will be delayed by x seconds (supported values are 1 to 9).`

`'BLOCKPIN'     : Mobile ID PIN will be blocked.`

`'RAT=x'        : Set Radio Access Technology to x (supported values are: A=Automatic, 0=2G, 2=3G, 7=4G).`

`'REBOOT'       : Execute 'reboot' linux command. (ATClient must be run as root)`

`'MAINTENANCE'  : Execute maintenance shell script. (ATClient must be run as root)`

##### Auto start at boot

On Raspberry Pi OS you can configure ATClient to auto-start (as forked process) at boot. Edit `/etc/rc.local` and before the `exit 0`, add:
`(/bin/sleep 80 && /usr/bin/java -Dconfig.file=/home/mid/atclient/atclient.cfg -Dlog.file=/home/mid/atclient/atclient.log -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" com.swisscom.atclient.ATClient) &`

The sleep of 80 seconds is recommended because the PLS8-E LTE terminal requires 30-40 seconds boot time.

> **Tip:** On modern Raspberry Pi OS, a systemd service is usually preferable to rc.local for auto-starting services. Example unit file:
> ```ini
> [Unit]
> Description=ATClient Mobile ID responder
> After=network.target
>
> [Service]
> Type=simple
> ExecStartPre=/bin/sleep 80
> ExecStart=/usr/bin/java -Dconfig.file=/home/mid/atclient/atclient.cfg -Dlog.file=/home/mid/atclient/atclient.log -Dlog4j.configurationFile=/home/mid/atclient/log4j2.xml -cp "/home/mid/atclient/class:/home/mid/atclient/lib/*" com.swisscom.atclient.ATClient
> Restart=on-failure
> User=mid
>
> [Install]
> WantedBy=multi-user.target
> ```

### License

This project is licensed under the Apache License 2.0 — see [LICENSE](LICENSE) for details.

Third-party dependencies bundled in `lib/` are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
