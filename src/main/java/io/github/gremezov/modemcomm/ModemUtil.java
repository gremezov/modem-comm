package io.github.gremezov.modemcomm;

import com.fazecast.jSerialComm.SerialPort;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class ModemUtil{

	public static String decode7BitGSM(byte[] gsm_rawdata){

		// function decode7bitGSM
		// Returns decoded 7-bit GSM text as a java string.

		// TODO: implement special character mapping

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

	public static byte[] encode7BitGSM(String ascii){

		// function encode7bitGSM
		// Returns the 7-bit encoded text as an array of bytes.

		int buffer = 0;	// must be 32-bit for the below code to work correctly
		int buf_len = 31;	// make sure MSB is never used or else java might turn the number negative and corrupt the data in the buffer
		int buf_free_bits = buf_len;
		byte[] gsm = new byte[(ascii.length()*7)/8 + (((ascii.length()*7)%8 > 0) ? 1 : 0)];

		// Conversion is done in the loop below using a buffer that takes 7 bits of ascii text from
		// one side and outputs 8 bits of 7-bit coded data on the other side.

		int a = 0;
		int g = 0;
		while(a < ascii.length()){
			while(buf_free_bits >= 7 && a < ascii.length()){
				buffer |= ((((byte)ascii.charAt(a++))&0x7F) << (buf_len-buf_free_bits));
				buf_free_bits -= 7;
			}
			while(buf_len-buf_free_bits >= 8){
				gsm[g++] = (byte)(buffer&0xFF);
				buffer = buffer >> 8;
				buf_free_bits += 8;
			}
		}
		if(buf_len-buf_free_bits > 0){
			gsm[g] = (byte)(buffer&0xFF);
		}

		return gsm;
	}

	public static String encodePDU(String phone_number, boolean international_format, String msg){

		// function encodePDU
		// Encodes the SMS message in PDU format. Returns a hex string.
		// For more info, see [1] and [2].
		//
		// [1]: http://www.gsm-modem.de/sms-pdu-mode.html
		// [2]: https://www.smsdeliverer.com/online-sms-pdu-encoder.aspx

		// encode phone number in BCD
		char[] pnum_semioct_chars = new char[phone_number.length() + (phone_number.length()%2)];
		for(int i = 0; i < pnum_semioct_chars.length; i+=2){
			pnum_semioct_chars[i] = (i == phone_number.length()-1) ? 'F' : phone_number.charAt(i+1);
			pnum_semioct_chars[i+1] = phone_number.charAt(i);
		}
		String pnum_semioct = new String(pnum_semioct_chars);

		String pdu = 	"00"+
						"11"+
						"00"+
						String.format("%02X", phone_number.length())+
						((international_format) ? "91" : "81")+
						pnum_semioct+
						"00"+
						"00"+
						"AA"+
						String.format("%02X", msg.length())+
						HexFormat.of().withUpperCase().formatHex(encode7BitGSM(msg));

		return pdu;
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
}
