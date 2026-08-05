package io.github.gremezov.modemcomm;

import com.fazecast.jSerialComm.SerialPort;
import java.io.PrintStream;

public class ModemUtilities{

	public static SerialPort[] scanForModemPorts(boolean verbose_output, int baudrate){

		// function scanForModemPorts
		// Scans for modems on all serial ports, prints progress messages, and returns an array of modem ports.
		// Returns an empty array if no modems were found. Returns null if no serial ports were found at all.

		int timeout = 2000; // timeout to wait for OK response, in milliseconds

		SerialPort[] availablePorts = SerialPort.getCommPorts();

		if(availablePorts.length == 0){
			Log.verboseOutput(verbose_output, "No serial ports found.\n\n");
			return null;
		}

		int modem_ports_cnt = 0;
		int[] modem_ports_indexes = new int[availablePorts.length];

		for(int i = 0; i < availablePorts.length; i++){
			SerialPort port = availablePorts[i];

			Log.verboseOutput(verbose_output, "Found serial port " + port.getSystemPortName()+"\n");
			Log.verboseOutput(verbose_output, "  description: " +port.getDescriptivePortName()+"\n");
			Log.verboseOutput(verbose_output, "  opening...");

			if(!port.openPort()){
				Log.verboseOutput(verbose_output, "failed\n");
				continue;
			}
			Log.verboseOutput(verbose_output, "success\n");
			Log.verboseOutput(verbose_output, "  sending ATQ0 command...");

			Serial.config(port, baudrate);

			PrintStream out = new PrintStream(port.getOutputStream());
			out.print("ATQ0\r\n");
			out.close();	// also helps flush the buffer

			long cmd_sent_time = System.nanoTime();
			boolean received_data = false;

			// loop for detecting "OK"
			responseScanLoop:
			while(true){
				if(port.bytesAvailable() > 0){
					received_data = true;
					String[] input_lines = Serial.readLines(port);
					if(input_lines.length > 0){
						for(String s : input_lines){
							if(s.length() >= 2 && s.substring(0,2).equals("OK")){
								Log.verboseOutput(verbose_output, "received OK\n");
								modem_ports_indexes[modem_ports_cnt++] = i;
								break responseScanLoop;
							}
						}
					}
				}
				if((System.nanoTime()-cmd_sent_time)/1000000 > timeout){
					if(received_data){
						Log.verboseOutput(verbose_output, "timed out before receiving OK (but received other data)\n");
					}else{
						Log.verboseOutput(verbose_output, "timed out before receiving any data\n");
					}
					break;
				}
			}
			port.closePort();
		}

		SerialPort[] modemPorts = new SerialPort[modem_ports_cnt];

		if(modem_ports_cnt > 0){
			for(int i = 0; i < modem_ports_cnt; i++){
				modemPorts[i] = availablePorts[modem_ports_indexes[i]];
			}
		}

		return modemPorts;
	}
}
