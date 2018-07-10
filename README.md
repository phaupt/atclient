# atclient
MobileID ATClient for Raspberry PI 3

### GSM device

This application has been successfully tested on Raspberry PI 3 B+ using a GSM device from the list below.
Baudrate set to default 9600.
- HCP HIT U8 PHS8 (3G) 
- HCP HIT U4 (LTE)


### Port selection

1. Unplug the modem
2. Run: ```sudo dmesg -c```
3. Plug in the modem and wait a few seconds
4. Run: ```sudo dmesg```

You may also trace the syslog while connecting the device: ```sudo tail -f /var/log/syslog```

### Compile and run the application

```
pi@raspberypi:~ $ git clone https://github.com/phaupt/atclient.git
pi@raspberypi:~ $ cd atclient
pi@raspberypi:~ $ mkdir class
pi@raspberypi:~/atclient $ javac -d ./class -cp "./lib/*" ./src/com/swisscom/atclient/*.java
pi@raspberypi:~/atclient $ java -Dlog4j.configuration=file:log4j.properties -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyACM1 UE
```

### Log

```
pi@raspberypi:~/atclient $ tail -f GsmClient.log
```
