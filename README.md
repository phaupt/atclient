# atclient
MobileID ATClient for Raspberry PI 3

### GSM Modem

This application has been successfully tested on Raspberry PI 3 B+ using a GSM Modem from the list below.
Baudrate set to default 9600.
* HCP HIT U8 PHS8 (3G) 
* HCP HIT U4 (LTE)

### Compile and run the application

pi@raspberypi:~ $ git clone https://github.com/phaupt/atclient.git
pi@raspberypi:~ $ cd atclient
pi@raspberypi:~ $ mkdir class
pi@raspberypi:~/atclient $ javac -d ./class -cp "./lib/*" ./src/com/swisscom/atclient/*.java
pi@raspberypi:~/atclient $ java -Dlog4j.configuration=file:log4j.properties -cp "./class:./lib/*" com.swisscom.atclient.GsmClient /dev/ttyACM1 UE

### Log

pi@raspberypi:~/atclient $ tail -f GsmClient.log

