package com.swisscom.atclient;

public class ATClient {

    public static void main(String[] args) {

        if (args.length == 1 && args[0].equalsIgnoreCase("--help")) {
            System.out.println(
                    "\n*** AT Client (SIMCom SIM8262E-M2 5G HAT) ***\n\n"
                            + "Usage: ATClient\n\n"
                            + "The ATClient runs the SIM Toolkit responder and heartbeat loop against a\n"
                            + "SIMCom SIM8262E-M2 5G HAT on the first matching USB port (or the port\n"
                            + "forced via -Dserial.port).\n\n"
                            + "\t-Dconfig.file=atclient.cfg             # Application config file\n"
                            + "\t-Dlog.file=atclient.log                # Application log file\n"
                            + "\t-Dlog4j.configurationFile=log4j2.xml   # Log4j config file\n"
                            + "\t-Dserial.port=/dev/simcom_at           # Force a specific serial port\n"
            );
            System.exit(0);
        }

        ATresponder atClient = new ATresponder((byte) 0);
        new Thread(atClient).start();
    }
}
