package com.swisscom.atclient;

import com.swisscom.atclient.ATresponder;

public class ATClient {

    public static void main(String[] args) throws InterruptedException {

        /**
         * DisplayText message may contain following keywords to invoke specific terminal responses.
         *
         * 'CANCEL'     : TerminalResponse '16' - Proactive SIM session terminated by user
         *
         * 'STKTIMEOUT' : TerminalResponse '18' - No response from user
         *
         * 'BLOCKPIN'   : Wrong PIN input. Mobile ID PIN will be blocked.
         *
         */

        ATresponder atClient = null;

        if (args.length == 1 && args[0].toUpperCase().equals("ER")) {
            atClient = new ATresponder((byte) 1);
            new Thread(atClient).start();
            
        } else if (args.length == 1 && args[0].toUpperCase().equals("AR")) {
            atClient = new ATresponder((byte) 2);
            new Thread(atClient).start();
            
        } else if (args.length == 0) {
            atClient = new ATresponder((byte) 0);
            new Thread(atClient).start();
            
        } else if (args.length == 1 && args[0].toUpperCase().equals("--HELP")) {
            System.out
                    .println("\n*** AT Client ***\n\n"
                            + "Usage: ATClient [<MODE>]\n\n"
                            + "<MODE>\tSwitch operation mode:\n"
                            + "\tER\tSwitch to Explicit Response (ER) and shutdown.\n"
                            + "\tAR\tSwitch to Automatic Response (AR) and shutdown.\n"
                            + "\n"
                            + "If no <MODE> argument found: Run user emulation with automatic serial port detection (ER operation mode only)\n"
                            + "\n"
                            + "\t-Dlog.file=atclient.log                # Application log file\n"
                            + "\t-Dlog4j.configurationFile=log4j2.xml   # Location of log4j.xml configuration file\n"
                            + "\t-Dserial.port=/dev/ttyACM1             # Select specific serial port (no automatic port detection)\n"
                    );

            System.exit(0);
        }

    }
}