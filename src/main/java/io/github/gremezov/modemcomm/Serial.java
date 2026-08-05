package io.github.gremezov.modemcomm;

import com.fazecast.jSerialComm.SerialPort;
import java.nio.charset.StandardCharsets;

public class Serial{

	public static void config(SerialPort port, int baudrate){

		// function config
		// Sets the baud rate and other settings of a serial port.

		port.setBaudRate(baudrate);
		port.setNumDataBits(8);
		port.setNumStopBits(SerialPort.ONE_STOP_BIT);
		port.setParity(SerialPort.NO_PARITY);
	}

	public static String read(SerialPort port){

		// function serialRead
		// Reads bytes from a serial port and converts them to a UTF-8 java string.

		byte[] buffer = new byte[port.bytesAvailable()];
		port.readBytes(buffer, buffer.length);
		return new String(buffer, StandardCharsets.UTF_8);
	}

	public static String[] readLines(SerialPort port){

		// function serialReadLines
		// Returns an array of strings containing the lines read from the serial port.

		return read(port).split("\\R");	// split based on newline
	}
}
