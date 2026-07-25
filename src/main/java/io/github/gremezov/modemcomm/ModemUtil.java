package io.github.gremezov.modemcomm;

import com.fazecast.jSerialComm.SerialPort;
import java.nio.charset.StandardCharsets;

public class ModemUtil{

	public static String decode7bitGSM(byte[] gsm_rawdata){

		// function decode7bitGSM
		// Returns decoded 7-bit GSM text as a java string.

		int buffer = 0;	// must be 32-bit for the below code to work correctly
		int buf_len = 31;	// make sure MSB is never used or else java might turn the number negative and corrupt the data in the buffer
		int buf_free_bits = buf_len;
		byte[] text8bit = new byte[(gsm_rawdata.length*8)/7];

		// Conversion is done in the loop below using a buffer that takes 8 bits of gsm text from
		// one side and outputs 7 bits of ascii text on the other side.

		int g = 0;
		int t = 0;
		while(g < gsm_rawdata.length){
			while(buf_free_bits >= 8 && g < gsm_rawdata.length){
				buffer |= ((gsm_rawdata[g++]&0xFF) << (buf_len-buf_free_bits));	// 0xFF used to prevent data corruption due to negative number representation in java
				buf_free_bits -= 8;
			}
			while(buf_len-buf_free_bits >= 7){
				text8bit[t++] = (byte)(buffer & 0x7F);
				buffer = buffer >> 7;
				buf_free_bits += 7;
			}
		}

		return new String(text8bit, StandardCharsets.UTF_8);
	}

	public static String serialRead(SerialPort port){

		// function serialRead
		// Reads bytes from a serial port and converts them to a UTF-8 java string.

		byte[] buffer = new byte[port.bytesAvailable()];
		port.readBytes(buffer, buffer.length);
		return new String(buffer, StandardCharsets.UTF_8);
	}

	public static String[] serialReadLines(SerialPort port){

		// function serialReadLines
		// Returns an array of strings containing the lines read from the serial port.

		return serialRead(port).split("\\R");	// split based on newline
	}

	public static int checkResponseOK(SerialPort modemport){

		// function checkResponseOK
		// Returns 0 if modem responds with an OK, -1 if ERROR, and -2 if timeout occurs
		// before any recognizeable modem response is detected.

		int timeout = 5000; // in milliseconds
		long cmd_sent_time = System.nanoTime();

		while(true){
			if(modemport.bytesAvailable() > 0){
				String[] input_lines = serialReadLines(modemport);
				if(input_lines.length > 0){
					for(String s : input_lines){
						if(s.length() >= 2 && s.substring(0,2).equals("OK")){
							return 0;
						} else if(s.length() >= 5 && s.substring(0,5).equals("ERROR")){
							return -1;
						}
					}
				}
			}
			if((System.nanoTime()-cmd_sent_time)/1000000 > timeout){
				return -2;
			}
		}
	}
}
